package com.taixu.tiangong.protocol.sse;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.taixu.tiangong.protocol.event.AgentEventEnvelope;
import com.taixu.tiangong.protocol.event.AgentEventJournal;
import com.taixu.tiangong.protocol.event.AgentEvent;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 极简 SSE 端点：把 {@link AgentEventJournal} 暴露成 {@code text/event-stream}。<br>
 * <br>
 * <b>为什么单独成一个类而不是塞进框架的 API 服务器</b>：协议层不应该认识
 * {@code @ApiEntranceNode} / 工作流路由；任何宿主（单机、Spring、Servlet）都可以
 * 在拿到一个 journal 后把它挂到自己的 HTTP 端口上。握手、回放、心跳、断连取消这四件事
 * 在这里一次做对，业务侧只需要提供 {@link SessionFactory}。<br>
 * <br>
 * <b>边界</b>：一个 GET 对应一次运行；不支持 POST 触发（触发语义属于工作流入口），
 * 也不做鉴权（应放在宿主网关/过滤器层，避免协议层伪造一套权限模型）。
 */
public final class AgentEventSseServer implements AutoCloseable {

    /** 心跳间隔：小于常见代理的 60s 空闲超时 */
    private static final long HEARTBEAT_SECONDS = 15L;

    public static final String DEFAULT_PATH = "/events";

    @FunctionalInterface
    public interface SessionFactory {
        Session create(AgentEventRequest request);
    }

    /**
     * 一次运行的会话。
     *
     * @param journal 事件日志（必须已绑定给运行）
     * @param cancel  客户端断开时的取消动作；null 表示调用 journal.cancel()
     */
    public record Session(AgentEventJournal journal, Runnable cancel) {
        public Session(AgentEventJournal journal) {
            this(journal, null);
        }
    }

    /**
     * 建立订阅时可以看到的请求信息（供宿主从 header/query 里取 runId 等）。
     */
    public record AgentEventRequest(
            String method,
            String path,
            Map<String, String> headers,
            String query,
            Long lastEventId
    ) {
    }

    private final String path;
    private final SessionFactory sessionFactory;
    private final HttpServer server;

    public AgentEventSseServer(int port, SessionFactory sessionFactory) {
        this(port, DEFAULT_PATH, sessionFactory);
    }

    public AgentEventSseServer(int port, String path, SessionFactory sessionFactory) {
        this.path = path == null || path.isBlank() ? DEFAULT_PATH : path;
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
        try {
            this.server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (IOException e) {
            throw new IllegalStateException("SSE 服务启动失败（端口 " + port + "）", e);
        }
        this.server.createContext("/", this::dispatch);
    }

    public void start() {
        server.start();
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            if (!path.equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            Long lastEventId = resolveLastEventId(exchange);
            AgentEventRequest request = new AgentEventRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    headersOf(exchange),
                    exchange.getRequestURI().getQuery(),
                    lastEventId);

            Session session;
            try {
                session = sessionFactory.create(request);
            } catch (RuntimeException e) {
                exchange.sendResponseHeaders(500, -1);
                return;
            }
            if (session == null || session.journal() == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
            exchange.sendResponseHeaders(200, 0);

            OutputStream out = exchange.getResponseBody();
            AgentEventJournal journal = session.journal();
            BlockingQueue<AgentEventEnvelope> queue = new LinkedBlockingQueue<>();
            try (AgentEventJournal.Subscription ignored =
                         journal.subscribe(queue::offer, lastEventId == null ? 0 : lastEventId)) {
                while (true) {
                    AgentEventEnvelope envelope;
                    try {
                        envelope = queue.poll(HEARTBEAT_SECONDS, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (envelope == null) {
                        AgentEventSseWriter.writeComment(out, "ping");
                        if (journal.isTerminal() && queue.isEmpty()) {
                            return;
                        }
                        continue;
                    }
                    AgentEventSseWriter.write(out, envelope);
                    if (envelope.event() instanceof AgentEvent.Done) {
                        return;
                    }
                }
            } catch (IOException e) {
                // 客户端断开：让上游停止，别让工作流继续跑给一个没人收的流
                cancel(session);
            }
        }
    }

    private static void cancel(Session session) {
        Runnable cancel = session.cancel();
        if (cancel != null) {
            cancel.run();
        } else {
            session.journal().cancel();
        }
    }

    private static Long resolveLastEventId(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Last-Event-ID");
        if (header != null && !header.isBlank()) {
            return parseLong(header);
        }
        String query = exchange.getRequestURI().getQuery();
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && "lastEventId".equals(pair.substring(0, eq))) {
                return parseLong(URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return null;
    }

    private static Long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, String> headersOf(HttpExchange exchange) {
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (values != null && !values.isEmpty()) {
                headers.put(name, values.getFirst());
            }
        });
        return headers;
    }
}
