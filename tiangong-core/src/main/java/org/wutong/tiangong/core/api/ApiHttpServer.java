package org.wutong.tiangong.core.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;
import org.wutong.tiangong.common.enums.RequestMethod;
import org.wutong.tiangong.core.api.entrance.EntranceRequest;
import org.wutong.tiangong.core.api.entrance.EntranceResponse;
import org.wutong.tiangong.core.api.entrance.EntranceRoute;
import org.wutong.tiangong.core.api.entrance.EntranceRouteRegistry;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * API HTTP 服务：基于 JDK HttpServer 的动态路由 HTTP 服务。<br>
 * <br>
 * 路由来源：{@link EntranceRouteRegistry}，工作流执行到 @ApiEntranceNode 时动态注册。<br>
 * <br>
 * 请求处理流程：<br>
 * <ol>
 *     <li>解析 HTTP 方法和路径</li>
 *     <li>从路由注册表查找匹配路由</li>
 *     <li>校验 headers / Content-Type / 必要查询参数</li>
 *     <li>读取请求体</li>
 *     <li>构造 {@link EntranceRequest} 投递到路由队列</li>
 *     <li>阻塞等待 ExportNode 回传响应（带超时）</li>
 * </ol>
 */
@Slf4j
public class ApiHttpServer implements AutoCloseable {

    /**
     * 默认响应超时（秒）。<br>
     * 对外开放（并可在 {@code agent.api.response-timeout-seconds} 覆盖）：一轮 LLM
     * 带思考 + 工具调用时几十秒很常见，写死的超时会把慢模型稳定打成 504。
     */
    public static final long DEFAULT_RESPONSE_TIMEOUT_SECONDS = 60;

    private final EntranceRouteRegistry routeRegistry;
    private final String host;
    private final int port;
    private final long responseTimeoutSeconds;
    private HttpServer server;

    public ApiHttpServer(EntranceRouteRegistry routeRegistry, String host, int port) {
        this(routeRegistry, host, port, DEFAULT_RESPONSE_TIMEOUT_SECONDS);
    }

    public ApiHttpServer(EntranceRouteRegistry routeRegistry, String host, int port,
                         long responseTimeoutSeconds) {
        this.routeRegistry = routeRegistry;
        this.host = host;
        this.port = port;
        this.responseTimeoutSeconds = responseTimeoutSeconds;
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(host, port), 0);
            server.createContext("/", this::dispatch);
            log.info("API HTTP 服务已启动: http://{}:{}", host, server.getAddress().getPort());
            server.start();
        } catch (IOException e) {
            log.warn("API HTTP 服务启动失败（端口可能被占用）: {}", e.getMessage());
        }
    }

    public int getBoundPort() {
        return server != null ? server.getAddress().getPort() : -1;
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        long startNanos = System.nanoTime();
        try {
            RequestMethod method;
            try {
                method = RequestMethod.valueOf(exchange.getRequestMethod());
            } catch (IllegalArgumentException e) {
                send(exchange, 405, "Method Not Allowed: " + exchange.getRequestMethod());
                return;
            }

            String path = exchange.getRequestURI().getPath();
            log.debug("收到请求: {} {} (线程={})", method, path, Thread.currentThread().getName());

            // 查找路由
            Optional<EntranceRoute> routeOpt = routeRegistry.find(method, path);
            if (routeOpt.isEmpty()) {
                log.debug("路由未匹配 → 404: {} {}", method, path);
                send(exchange, 404, "Not Found: " + method + " " + path);
                return;
            }

            EntranceRoute route = routeOpt.get();

            // 校验请求头
            String headerError = validateHeaders(route, exchange);
            if (headerError != null) {
                send(exchange, 400, headerError);
                return;
            }

            // 校验 Content-Type
            String contentTypeError = validateContentType(route, exchange);
            if (contentTypeError != null) {
                send(exchange, 415, contentTypeError);
                return;
            }

            // 校验查询参数
            Map<String, String> queryParams = parseQuery(exchange.getRequestURI().getQuery());
            String paramError = validateParams(route, queryParams);
            if (paramError != null) {
                send(exchange, 400, paramError);
                return;
            }

            // 读取请求体
            String body = readBody(exchange);

            // 构造请求
            EntranceRequest request = new EntranceRequest(
                    exchange.getRequestMethod(),
                    path,
                    body,
                    extractHeaders(exchange),
                    queryParams,
                    null,
                    new java.util.concurrent.CompletableFuture<>()
            );

            // 投递到路由
            if (!route.submit(request)) {
                send(exchange, 503, "路由已关闭");
                return;
            }

            // 等待响应
            EntranceResponse response;
            try {
                response = request.responseFuture()
                        .get(responseTimeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                // 超时 = 客户端放弃等待。必须主动作废响应 Future：
                // 不 cancel 的话工作流会一路跑到 EXPORT，把结果 complete 进一个没人接的 Future，
                // 而 complete() 对"无人等待"是静默成功的 —— 于是表现为
                //「请求早就 504 了，CPU 还在满负荷跑，且日志里什么都没有」。
                request.responseFuture().cancel(false);
                log.warn("响应超时（{}s）：{} {} —— 响应关联已作废，工作流仍在运行（{}ms）。"
                                + "长任务不要用同步入口：改成「受理 + runId + 轮询产物」",
                        responseTimeoutSeconds, method, path,
                        (System.nanoTime() - startNanos) / 1_000_000);
                send(exchange, 504, "Gateway Timeout: 工作流未在超时时间内响应");
                return;
            } catch (Exception e) {
                log.error("等待工作流响应失败", e);
                send(exchange, 500, "Internal Server Error: " + e.getMessage());
                return;
            }

            // 返回响应
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.debug("请求完成: {} {} → {} ({}ms)", method, path, response.status(), elapsedMs);
            writeResponse(exchange, response);

        } catch (Exception e) {
            log.error("API 请求处理失败", e);
            send(exchange, 500, "Internal Server Error: " + e.getMessage());
        } finally {
            exchange.close();
        }
    }

    // ==================== 请求校验 ====================

    private String validateHeaders(EntranceRoute route, HttpExchange exchange) {
        for (String required : route.headers()) {
            if (exchange.getRequestHeaders().getFirst(required) == null) {
                return "Bad Request: 缺少请求头 " + required;
            }
        }
        return null;
    }

    private String validateContentType(EntranceRoute route, HttpExchange exchange) {
        if (route.consumes().length == 0) {
            return null;
        }
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null) {
            return "Unsupported Media Type: 缺少 Content-Type";
        }
        for (String accepted : route.consumes()) {
            if (contentType.contains(accepted)) {
                return null;
            }
        }
        return "Unsupported Media Type: Content-Type " + contentType + " 不被接受";
    }

    private String validateParams(EntranceRoute route, Map<String, String> queryParams) {
        for (String required : route.requiredParams()) {
            if (!queryParams.containsKey(required)) {
                return "Bad Request: 缺少查询参数 " + required;
            }
        }
        return null;
    }

    // ==================== 工具方法 ====================

    private String readBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        if (bytes.length == 0) {
            return null;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private Map<String, String> extractHeaders(HttpExchange exchange) {
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> {
            if (!v.isEmpty()) {
                headers.put(k, v.getFirst());
            }
        });
        return headers;
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) {
            return params;
        }
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                params.put(pair.substring(0, idx), pair.substring(idx + 1));
            } else {
                params.put(pair, "");
            }
        }
        return params;
    }

    private void writeResponse(HttpExchange exchange, EntranceResponse response) throws IOException {
        if (response.body() == null) {
            exchange.sendResponseHeaders(response.status(), -1);
            return;
        }
        if (response.contentType() != null) {
            exchange.getResponseHeaders().set("Content-Type", response.contentType());
        }
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(response.status(), body.length);
        exchange.getResponseBody().write(body);
    }

    private void send(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
            log.info("API HTTP 服务已关闭");
        }
    }
}
