package com.taixu.tiangong.protocol.sse;

import com.taixu.tiangong.protocol.event.AgentEventEnvelope;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 客户端 SDK：连接 SSE 端点，按类型化事件读取，支持 {@code Last-Event-ID} 续传。<br>
 * <br>
 * <h2>断点续传的真实语义</h2>
 * 重连不是「让服务端重发正文」，而是把最后一次收到的 {@code seq} 放进
 * {@code Last-Event-ID}，服务端回放 {@code seq > last} 的事件。客户端因此只需要
 * 保存一个 long，不需要自己维护消息拼接状态。<br>
 * <br>
 * <b>边界</b>：服务端重启后内存日志丢失，重连只能从当前可回放范围开始；
 * 跨进程续传需要持久化日志，本类不假装支持。
 */
public final class AgentEventClient {

    private final URI endpoint;
    private final HttpClient http;

    public AgentEventClient(URI endpoint) {
        this(endpoint, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    public AgentEventClient(URI endpoint, HttpClient http) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.http = Objects.requireNonNull(http, "http");
    }

    /**
     * 建立连接。
     *
     * @param lastEventId 上次收到的最后 seq；null/空表示从头开始
     */
    public Connection connect(String lastEventId) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .GET()
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache");
        if (lastEventId != null && !lastEventId.isBlank()) {
            builder.header("Last-Event-ID", lastEventId);
        }
        HttpResponse<InputStream> response = http.send(builder.build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            try (InputStream body = response.body()) {
                throw new IOException("SSE 连接失败: HTTP " + response.statusCode()
                        + " " + new String(body.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return new Connection(response.body());
    }

    /** 阻塞读到 {@code done}，返回本次收到的事件（含 done） */
    public List<AgentEventEnvelope> readUntilDone() throws IOException, InterruptedException {
        List<AgentEventEnvelope> events = new ArrayList<>();
        try (Connection connection = connect(null)) {
            AgentEventEnvelope envelope;
            while ((envelope = connection.next()) != null) {
                events.add(envelope);
                if (envelope.event() instanceof com.taixu.tiangong.protocol.event.AgentEvent.Done) {
                    break;
                }
            }
        }
        return events;
    }

    /**
     * 后台订阅：断线后带 {@code Last-Event-ID} 自动重连，直到收到 done 或手动关闭。<br>
     * 每轮重连都从最后收到的 seq 之后继续，不会重复消费已处理事件。
     */
    public AutoCloseable subscribe(Consumer<AgentEventEnvelope> onEvent,
                                   Consumer<Throwable> onError) {
        Objects.requireNonNull(onEvent, "onEvent");
        Objects.requireNonNull(onError, "onError");
        Subscription subscription = new Subscription(onEvent, onError);
        Thread.ofVirtual().name("tiangong-sse-client").start(subscription::run);
        return subscription;
    }

    public static final class Connection implements AutoCloseable {

        private final InputStream input;
        private final BufferedReader reader;

        private Connection(InputStream input) {
            this.input = input;
            this.reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        }

        /** 下一条事件；流结束返回 null */
        public AgentEventEnvelope next() throws IOException {
            return AgentEventSseReader.next(reader);
        }

        @Override
        public void close() throws IOException {
            input.close();
        }
    }

    private final class Subscription implements AutoCloseable {

        private final Consumer<AgentEventEnvelope> onEvent;
        private final Consumer<Throwable> onError;
        private volatile boolean closed;
        private volatile Connection active;

        private Subscription(Consumer<AgentEventEnvelope> onEvent, Consumer<Throwable> onError) {
            this.onEvent = onEvent;
            this.onError = onError;
        }

        private void run() {
            long lastSeq = 0;
            while (!closed) {
                try (Connection connection = connect(lastSeq > 0 ? Long.toString(lastSeq) : null)) {
                    active = connection;
                    AgentEventEnvelope envelope;
                    while (!closed && (envelope = connection.next()) != null) {
                        lastSeq = envelope.seq();
                        onEvent.accept(envelope);
                        if (envelope.event() instanceof com.taixu.tiangong.protocol.event.AgentEvent.Done) {
                            return;
                        }
                    }
                } catch (IOException e) {
                    if (!closed) {
                        onError.accept(e);
                    }
                } catch (RuntimeException e) {
                    // onEvent 抛异常：保持连接层不静默死线程，交给调用方
                    if (!closed) {
                        onError.accept(e);
                    }
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (closed) {
                    return;
                }
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        @Override
        public void close() {
            closed = true;
            Connection connection = active;
            if (connection != null) {
                try {
                    connection.close();
                } catch (IOException ignored) {
                    // 关闭时的 IO 异常已无补救意义
                }
            }
        }
    }
}
