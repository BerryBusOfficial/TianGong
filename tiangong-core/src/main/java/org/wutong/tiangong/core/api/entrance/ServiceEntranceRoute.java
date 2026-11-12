package org.wutong.tiangong.core.api.entrance;

import lombok.extern.slf4j.Slf4j;
import org.wutong.tiangong.core.agent.AgentNodeDefinition;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 服务入口路由：按 {@code url} 寻址的进程内调用通道条目。<br>
 * <br>
 * 工作流执行到 {@code @ServiceEntranceNode} 时创建并注册到
 * {@link ServiceEntranceRegistry}；工作流结束时注销。<br>
 * <br>
 * 与 {@link EntranceRoute}（HTTP 路由）同构的队列 / 并发 / 关闭机制，
 * 但<b>没有协议语义</b>：没有 methods / headers / consumes / params ——
 * 进程内调用不需要这些，寻址就是 url 本身。
 */
@Slf4j
public class ServiceEntranceRoute {

    private final String url;
    private final String workflowId;
    private final AgentNodeDefinition node;
    private final int concurrencyLimit;

    /** 待处理请求队列 */
    private final BlockingQueue<EntranceRequest> pendingRequests = new LinkedBlockingQueue<>();

    /** 当前活跃并发数 */
    private final AtomicInteger activeCount = new AtomicInteger(0);

    /** 路由是否已关闭（工作流结束时） */
    private volatile boolean closed = false;

    public ServiceEntranceRoute(String url, String workflowId, AgentNodeDefinition node, int concurrencyLimit) {
        this.url = url;
        this.workflowId = workflowId;
        this.node = node;
        this.concurrencyLimit = concurrencyLimit;
    }

    public String url() {
        return url;
    }

    public String workflowId() {
        return workflowId;
    }

    public AgentNodeDefinition node() {
        return node;
    }

    public int concurrencyLimit() {
        return concurrencyLimit;
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * 投递请求到队列。如果路由已关闭，立即以 503 完成响应。
     */
    public boolean submit(EntranceRequest request) {
        if (closed) {
            request.responseFuture().complete(EntranceResponse.error(503, "服务入口已关闭"));
            return false;
        }
        return pendingRequests.offer(request);
    }

    /**
     * 带超时取下一个请求（通道层每 ~200ms 轮询，让取消/关闭信号能打进驻留点）。
     */
    public EntranceRequest poll(long timeout, TimeUnit unit) throws InterruptedException {
        return pendingRequests.poll(timeout, unit);
    }

    /**
     * 检查是否允许新的并发调用。
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
     * 关闭路由：工作流结束/取消时调用，所有待处理请求返回 503。
     */
    public void close() {
        closed = true;
        EntranceRequest req;
        while ((req = pendingRequests.poll()) != null) {
            req.responseFuture().complete(EntranceResponse.error(503, "服务入口已关闭"));
        }
    }

    @Override
    public String toString() {
        return "ServiceEntranceRoute{" + url + ", workflow=" + workflowId
                + ", node=" + (node == null ? "-" : node.name()) + "}";
    }
}
