package com.taixu.tiangong.llm.builder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.taixu.tiangong.common.exception.SystemRuntimeException;
import com.taixu.tiangong.common.util.JsonUtil;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RequestBuilder {

    /**
     * 框架内置的 LLM 请求超时默认值。<br>
     * <b>它只是默认值，不再是唯一值</b>：历史上这里被写死成 5 分钟且没有任何配置入口，
     * 本地小模型（开思考 + 工具调用）单次请求常常接近这个天花板，应用却完全无法调。
     * 现在可通过 {@link #setDefaultTimeout(Duration)} 全局改、或 {@link #timeout(Duration)} 单次改。
     */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofMinutes(5);

    /**
     * 全局默认超时。配置键为 {@code llm.request-timeout}（秒），由 core 在配置加载后注入；
     * 未配置时保持 {@link #DEFAULT_REQUEST_TIMEOUT}。
     */
    private static volatile Duration defaultTimeout = DEFAULT_REQUEST_TIMEOUT;

    private final List<BuilderConsumer> consumers = new ArrayList<>();
    private final HttpRequest.Builder builder = HttpRequest.newBuilder();
    private URI uri;
    private Method method = Method.GET;
    private String body;
    private boolean loadNonBody = false;
    private Duration timeout;

    public RequestBuilder() {
    }

    /**
     * 读取当前全局默认超时。
     */
    public static Duration defaultTimeout() {
        return defaultTimeout;
    }

    /**
     * 设置全局默认超时；之后新建的 {@code RequestBuilder} 都会用它。<br>
     * 已有的 builder 不受影响（它们在 build 时才取值，但你改的是静态默认值，
     * 未显式设过 timeout 的 builder 会跟着变——这是刻意的：配置注入发生在启动早期）。
     *
     * @param timeout 正的时间长度；null / 0 / 负数一律拒绝，避免产生「永不超时」的请求
     */
    public static void setDefaultTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("LLM 请求超时必须是正数: " + timeout);
        }
        defaultTimeout = timeout;
    }

    /**
     * 为本次请求单独指定超时，优先级高于 {@link #setDefaultTimeout(Duration)}。
     */
    public RequestBuilder timeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("LLM 请求超时必须是正数: " + timeout);
        }
        this.timeout = timeout;
        return this;
    }

    /**
     * 拼 baseUrl 与端点路径。<br>
     * 除了对齐斜杠，还要处理一个很常见的坑：<b>base_url 已经带版本前缀</b>。
     * OpenAI 生态里 {@code https://host/v1} 是标准写法（SDK 也是这么要求的），
     * 而各 Api 的端点同样以 {@code /v1} 开头 —— 纯拼接会得到 {@code /v1/v1/chat/completions}
     * 然后 404，且不报任何可读性好的错误。所以这里发现两边都有 {@code /v1} 时只留一个。
     */
    protected String endpoint(String baseUrl, String endpoint) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String path = endpoint.startsWith("/") ? endpoint : "/" + endpoint;

        if (base.endsWith("/v1") && path.startsWith("/v1/")) {
            path = path.substring("/v1".length());
        }
        return base + path;
    }

    public RequestBuilder uri(String baseUrl, String endpoint) {
        this.uri = URI.create(endpoint(baseUrl, endpoint));
        return this;
    }

    public RequestBuilder uri(String uri) {
        this.uri = URI.create(uri);
        return this;
    }

    public RequestBuilder uri(URI uri) {
        this.uri = uri;
        return this;
    }

    public RequestBuilder method(Method method) {
        this.method = method;
        return this;
    }

    public RequestBuilder method(String method) {
        switch (method.toUpperCase()) {
            case "GET":
                this.method = Method.GET;
                break;
            case "POST":
                this.method = Method.POST;
                break;
            case "DELETE":
                this.method = Method.DELETE;
                break;
            case "PUT":
                this.method = Method.PUT;
                break;
            case "HEAD":
                this.method = Method.HEAD;
                break;
        }
        return this;
    }

    public RequestBuilder body(Map<String, Object> body) {
        if (loadNonBody) {
            throw new SystemRuntimeException("body 已设置为空");
        }
        if (body == null || body.isEmpty()) {
            return nonBody();
        }
        try {
            this.body = JsonUtil.toJson(body);
        } catch (JsonProcessingException e) {
            throw new SystemRuntimeException("处理JSON失败：%s".formatted(e.getMessage()));
        }
        return this;
    }

    public RequestBuilder body(String body) {
        if (loadNonBody) {
            throw new SystemRuntimeException("body 已设置为空");
        }
        if (body == null || body.isEmpty()) {
            return nonBody();
        }
        this.body = body;
        return this;
    }

    public RequestBuilder nonBody() {
        this.body = null;
        this.loadNonBody = true;
        return this;
    }

    public RequestBuilder auth(String apiKey) {
        this.consumers.add(builder -> {
            builder.header("Authorization", "Bearer " + apiKey);
            return builder;
        });
        return this;
    }

    public RequestBuilder header(String key, String value) {
        this.consumers.add(builder -> {
            builder.header(key, value);
            return builder;
        });
        return this;
    }

    public HttpRequest build() {
        if (uri == null) {
            throw new SystemRuntimeException("构建丢失 uri 参数");
        }
        // ⚠ 无 body 时必须给 noBody() 而不是 null：JDK 的 method(name, publisher) 对
        // publisher 做 requireNonNull，GET/HEAD 传 null 会直接 NPE。
        // 典型受害者是 models 接口 —— 它就是「GET + 空 body」，一调就崩。
        HttpRequest.BodyPublisher publisher = (loadNonBody || body == null)
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);

        HttpRequest.Builder builder = this.builder.uri(this.uri)
                .method(this.method.name(), publisher)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(this.timeout != null ? this.timeout : defaultTimeout);
        for (BuilderConsumer consumer : this.consumers) {
            builder = consumer.build(builder);
        }
        return builder.build();

    }

    public enum Method {
        GET,
        POST,
        DELETE,
        PUT,
        HEAD,
    }

    @FunctionalInterface
    interface BuilderConsumer {
        HttpRequest.Builder build(HttpRequest.Builder builder);
    }

}
