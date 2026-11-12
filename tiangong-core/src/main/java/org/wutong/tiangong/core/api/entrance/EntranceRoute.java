package org.wutong.tiangong.core.api.entrance;

import lombok.extern.slf4j.Slf4j;
import org.wutong.tiangong.common.enums.RequestMethod;
import org.wutong.tiangong.core.agent.AgentNodeDefinition;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * EntranceNode 路由：动态注册的 HTTP 路由条目。<br>
 * <br>
 * 工作流执行到 @ApiEntranceNode 时创建并注册到 {@link EntranceRouteRegistry}。<br>
 * 工作流结束时从注册表移除。<br>
 * <br>
 * 核心机制：<br>
 * <ul>
 *     <li>{@link #submit(EntranceRequest)}：ApiHttpServer 投递请求到 {@link #pendingRequests}</li>
 *     <li>{@link #registerResponse(String, CompletableFuture)}：contextId → 响应 Future 关联</li>
 *     <li>{@link #completeResponse(String, EntranceResponse)}：按 contextId 完成响应</li>
 * </ul>
 * ⚠️ <b>上面这条「contextId 关联表」目前没有接线</b>：真实回传走的是
 * {@link EntranceRequest#responseFuture()}（{@code ApiHttpServer} 投递请求 → EXPORT 直接
 * complete 该 Future）。这两个方法与 {@code pendingResponses} 暂无调用方，
 * 留着是为了将来按 contextId 做多请求关联时不必改动调用方。
 * </ul>
 * <br>
 * 并发控制：{@link #concurrencyLimit} 限制同时处理的请求数，超过返回 429。
 */
@Slf4j
public class EntranceRoute {

    private final String workflowId;
    private final String routePath;
    private final RequestMethod[] methods;
    private final String[] headers;
    private final String[] consumes;
    private final String[] produces;
    private final String[] requiredParams;
    private final int concurrencyLimit;
    private final AgentNodeDefinition node;
    /**
     * 路由是否锚定到绝对路径（不带 workflowId 前缀）。<br>
     * 由 {@code @ApiEntranceNode(absolute = true)} 指定 —— workflowId 是运行期随机串，
     * 客户端没法预先知道 URL；回调 / Webhook 这类要提前把地址配给外部系统的场景必须能固定。
     */
    private final boolean absolute;

    /** 待处理请求队列 */
    private final BlockingQueue<EntranceRequest> pendingRequests = new LinkedBlockingQueue<>();

    /** 响应关联表：contextId → 响应 Future */
    private final ConcurrentMap<String, CompletableFuture<EntranceResponse>> pendingResponses = new ConcurrentHashMap<>();

    /** 当前活跃并发数 */
    private final AtomicInteger activeCount = new AtomicInteger(0);

    /** 路由是否已关闭（工作流结束时） */
    private volatile boolean closed = false;

    public EntranceRoute(String workflowId, String routePath, RequestMethod[] methods,
                         String[] headers, String[] consumes, String[] produces,
                         String[] requiredParams, int concurrencyLimit,
                         AgentNodeDefinition node) {
        this(workflowId, routePath, methods, headers, consumes, produces,
                requiredParams, concurrencyLimit, node, false);
    }

    public EntranceRoute(String workflowId, String routePath, RequestMethod[] methods,
                         String[] headers, String[] consumes, String[] produces,
                         String[] requiredParams, int concurrencyLimit,
                         AgentNodeDefinition node, boolean absolute) {
        this.workflowId = workflowId;
        this.routePath = routePath;
        this.methods = methods;
        this.headers = headers;
        this.consumes = consumes;
        this.produces = produces;
        this.requiredParams = requiredParams;
        this.concurrencyLimit = concurrencyLimit;
        this.node = node;
        this.absolute = absolute;
    }

    public String workflowId() {
        return workflowId;
    }

    /**
     * 完整路由路径：{@code absolute=true} 时为 {@code /{routePath}}，
     * 否则为 {@code /{workflowId}/{routePath}}。
     */
    public String fullPath() {
        String base = absolute ? "" : "/" + workflowId;
        if (routePath == null || routePath.isEmpty()) {
            return base.isEmpty() ? "/" : base;
        }
        if (routePath.startsWith("/")) {
            return base + routePath;
        }
        return base + "/" + routePath;
    }

    /**
     * 是否为绝对路由（不带 workflowId 前缀）。
     */
    public boolean absolute() {
        return absolute;
    }

    public RequestMethod[] methods() {
        return methods;
    }

    public String[] headers() {
        return headers;
    }

    public String[] consumes() {
        return consumes;
    }

    public String[] produces() {
        return produces;
    }

    public String[] requiredParams() {
        return requiredParams;
    }

    public int concurrencyLimit() {
        return concurrencyLimit;
    }

    public AgentNodeDefinition node() {
        return node;
    }

    /**
     * 投递请求到队列。如果路由已关闭，返回 503。
     */
    public boolean submit(EntranceRequest request) {
        if (closed) {
            request.responseFuture().complete(EntranceResponse.error(503, "路由已关闭"));
            return false;
        }
        return pendingRequests.offer(request);
    }

    /**
     * 阻塞等待下一个请求。
     */
    public EntranceRequest take() throws InterruptedException {
        return pendingRequests.take();
    }

    /**
     * 尝试获取下一个请求（非阻塞）。
     */
    public EntranceRequest poll() {
        return pendingRequests.poll();
    }

    /**
     * 带超时获取下一个请求。<br>
     * <br>
     * 由 {@link org.wutong.tiangong.core.agent.entrance.HttpEntranceChannel#await} 使用：
     * 通道层每 ~200ms 轮询一次，让 {@code AgentContext#isCancelled()} 与通道关闭信号
     * 能打进驻留点（原本 {@link #take()} 阻塞且不可中断，shutdown 与取消都进不去）。
     */
    public EntranceRequest poll(long timeout, TimeUnit unit) throws InterruptedException {
        return pendingRequests.poll(timeout, unit);
    }

    /**
     * 检查是否允许新的并发请求。
     */
    public boolean tryAcquireConcurrency() {
        if (concurrencyLimit == 0) {
            return true;
        }
        while (true) {
            int current = activeCount.get();
            if (current >= concurrencyLimit) {
                return false;
            }
            if (activeCount.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /**
     * 释放一个并发槽位。
     */
    public void releaseConcurrency() {
        if (concurrencyLimit != 0) {
            activeCount.decrementAndGet();
        }
    }

    /**
     * 注册响应关联：contextId → Future。
     */
    /**
     * @deprecated <b>死代码，无调用点</b>。真实的响应回传链路是
     *             {@code ApiHttpServer → EntranceRequest.responseFuture() → EXPORT 节点直接 complete}。
     *             照着这个方法名写会走上一条永远不生效的路 —— 保留它只为将来真做
     *             「按 contextId 关联多请求」时不必改调用方，但别现在用。
     */
    @Deprecated
    public void registerResponse(String contextId, CompletableFuture<EntranceResponse> future) {
        pendingResponses.put(contextId, future);
    }

    /**
     * 完成响应：ExportNode 调用。
     */
    /**
     * @deprecated 同 {@link #registerResponse}：无调用点，响应不走这里。
     */
    @Deprecated
    public void completeResponse(String contextId, EntranceResponse response) {
        CompletableFuture<EntranceResponse> future = pendingResponses.remove(contextId);
        if (future != null) {
            future.complete(response);
        } else {
            log.warn("响应关联丢失: contextId={}, 可能已超时或工作流已取消", contextId);
        }
    }

    /**
     * 关闭路由：工作流结束/取消时调用。<br>
     * 所有待处理请求返回 503，所有待响应请求返回 500。
     */
    public void close() {
        closed = true;
        // 拒绝所有待处理请求
        EntranceRequest req;
        while ((req = pendingRequests.poll()) != null) {
            req.responseFuture().complete(EntranceResponse.error(503, "工作流已结束"));
        }
        // 失败所有待响应请求
        for (var entry : pendingResponses.entrySet()) {
            entry.getValue().complete(EntranceResponse.error(500, "工作流异常终止"));
        }
        pendingResponses.clear();
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public String toString() {
        return "EntranceRoute{" + fullPath() + ", methods=" + String.join(", ",
                java.util.Arrays.stream(methods).map(Enum::name).toList()) + "}";
    }

}
