package com.taixu.tiangong.core.function;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.taixu.tiangong.common.thread.VirtualThreads;
import com.taixu.tiangong.common.util.JsonUtil;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * HITL 审批管理端点（JDK HttpServer，无额外依赖）。<br>
 * <br>
 * <pre>
 * GET  /approvals                待审批列表
 * GET  /approvals/{id}           单个待审批
 * POST /approvals/{id}/approve   body {"approver":"alice"}
 * POST /approvals/{id}/reject    body {"approver":"alice"}
 * </pre>
 * <br>
 * <b>鉴权</b>：构造时必须提供 bearer token；没有 token 直接拒绝启动，避免把审批端点
 * 裸奔到内网。token 只用于本端点，不复用 Central/仓库凭据。<br>
 * <b>边界</b>：内存 pending 由 {@link HumanApprovalService} 管；进程重启即丢。生产多实例
 * 需要把 ticket 落库并让所有实例共享同一个审批事件源。
 */
public final class HumanApprovalHttpServer implements AutoCloseable {

    public static final String DEFAULT_PATH = "/approvals";

    private final String path;
    private final HumanApprovalService service;
    private final byte[] token;
    private final HttpServer server;
    private final ExecutorService executor;

    public HumanApprovalHttpServer(int port, HumanApprovalService service, String bearerToken) {
        if (service == null) {
            throw new IllegalArgumentException("service 不能为空");
        }
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new IllegalArgumentException("审批端点必须配置 bearerToken");
        }
        this.path = DEFAULT_PATH;
        this.service = service;
        this.token = bearerToken.getBytes(StandardCharsets.UTF_8);
        this.executor = VirtualThreads.newPerTaskExecutor();
        try {
            this.server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (IOException e) {
            throw new IllegalStateException("审批服务启动失败（端口 " + port + "）", e);
        }
        server.setExecutor(executor);
        server.createContext("/", this::handle);
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
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) {
        try (exchange) {
            if (!authorized(exchange)) {
                send(exchange, 401, Map.of("error", "unauthorized"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (!path.equals(this.path) && !path.startsWith(this.path + "/")) {
                send(exchange, 404, Map.of("error", "not found"));
                return;
            }
            String rest = path.substring(this.path.length());
            String[] parts = rest.isEmpty() ? new String[0] : rest.substring(1).split("/");
            String method = exchange.getRequestMethod().toUpperCase();

            if (parts.length == 0) {
                if (!"GET".equals(method)) {
                    send(exchange, 405, Map.of("error", "method not allowed"));
                    return;
                }
                send(exchange, 200, Map.of("items", service.pending()));
                return;
            }

            String id = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            if (parts.length == 1) {
                if (!"GET".equals(method)) {
                    send(exchange, 405, Map.of("error", "method not allowed"));
                    return;
                }
                service.find(id).ifPresentOrElse(
                        item -> sendQuietly(exchange, 200, item),
                        () -> sendQuietly(exchange, 404, Map.of("error", "not found")));
                return;
            }

            if (parts.length == 2 && ("approve".equals(parts[1]) || "reject".equals(parts[1]))) {
                if (!"POST".equals(method)) {
                    send(exchange, 405, Map.of("error", "method not allowed"));
                    return;
                }
                String approver = readApprover(exchange);
                if (approver == null || approver.isBlank()) {
                    send(exchange, 400, Map.of("error", "approver is required"));
                    return;
                }
                boolean ok = "approve".equals(parts[1])
                        ? service.approve(id, approver)
                        : service.reject(id, approver);
                if (!ok) {
                    send(exchange, 409, Map.of("error", "ticket not pending"));
                    return;
                }
                send(exchange, 200, Map.of("ok", true));
                return;
            }

            send(exchange, 404, Map.of("error", "not found"));
        } catch (Exception e) {
            // 错误响应本身可能已经发出；尽力而为，不把内部异常细节暴露给审批端。
            sendQuietly(exchange, 500, Map.of("error", "internal error"));
        }
    }

    private boolean authorized(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        byte[] presented = header.substring("Bearer ".length()).trim()
                .getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(token, presented);
    }

    private String readApprover(HttpExchange exchange) {
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && "approver".equals(pair.substring(0, eq))) {
                    return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                }
            }
        }
        try {
            byte[] body = exchange.getRequestBody().readNBytes(16 * 1024);
            if (body.length == 0) {
                return null;
            }
            Map<String, Object> json = JsonUtil.bodyToMap(new String(body, StandardCharsets.UTF_8));
            Object approver = json.get("approver");
            return approver == null ? null : String.valueOf(approver);
        } catch (Exception e) {
            return null;
        }
    }

    private static void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JsonUtil.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sendQuietly(HttpExchange exchange, int status, Object body) {
        try {
            send(exchange, status, body);
        } catch (IOException ignored) {
            // 客户端可能已断开
        }
    }
}
