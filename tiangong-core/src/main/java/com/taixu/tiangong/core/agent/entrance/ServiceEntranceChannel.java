package com.taixu.tiangong.core.agent.entrance;

import lombok.extern.slf4j.Slf4j;
import com.taixu.tiangong.common.agent.context.AgentContext;
import com.taixu.tiangong.core.agent.AgentNodeDefinition;
import com.taixu.tiangong.core.api.entrance.EntranceRequest;
import com.taixu.tiangong.core.api.entrance.ServiceEntranceRegistry;
import com.taixu.tiangong.core.api.entrance.ServiceEntranceRoute;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 服务入口通道：把进程内服务调用的"地址注册 + 取请求"收编到 {@link EntranceChannel}。<br>
 * <br>
 * 与 {@link HttpEntranceChannel} 的关系：同一套骨架（每 200ms 轮询、取消/关闭可打入、
 * 不写检查点），区别只在寻址方式 —— HTTP 是 method+path 匹配，服务是 url 精确匹配；
 * 请求来源也不是 HTTP 服务器，而是 {@link ServiceEntranceRegistry#call} 的投递。<br>
 * <br>
 * 通道本身没有"启停"语义：没有独立服务器资源要管理，{@code start()} / {@code close()}
 * 为空操作（与 HTTP 通道一致）。
 */
@Slf4j
public class ServiceEntranceChannel implements EntranceChannel<EntranceRequest> {

    /** await 轮询粒度：决定取消/关闭信号的最迟响应延迟 */
    static final long POLL_MILLIS = 200L;

    private final ServiceEntranceRegistry registry;

    public ServiceEntranceChannel(ServiceEntranceRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return "service";
    }

    @Override
    public void start() {
    }

    @Override
    public void close() {
    }

    /**
     * 阻塞取得下一条服务调用请求。<br>
     * <br>
     * 返回 {@code null} 的情形：该 workflowId 没有已注册地址（未注册/已注销）、
     * 地址已关闭、或工作流被取消。返回非 {@code null} 表示取得一次调用，
     * 框架 fork 副本在新线程处理。
     */
    @Override
    public EntranceRequest await(String workflowId, AgentContext context) throws InterruptedException {
        List<ServiceEntranceRoute> routes = registry.findByWorkflowId(workflowId);
        if (routes == null || routes.isEmpty()) {
            return null;
        }
        // 与 HTTP 通道同策略：轮流 poll 该工作流的全部地址 ——
        // 一条工作流注册了多个 @ServiceEntranceNode 时，只 poll 第一条会让其余静默收不到调用。
        while (!context.isCancelled()) {
            boolean allClosed = true;
            for (ServiceEntranceRoute route : routes) {
                if (route.isClosed()) {
                    continue;
                }
                allClosed = false;
                EntranceRequest req = route.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (req != null) {
                    return req;
                }
            }
            if (allClosed) {
                return null;
            }
        }
        return null;
    }

    @Override
    public String toBody(EntranceRequest input) {
        // 调用方传入的就是 JSON 文本（或 null）；框架按入口方法参数类型解析
        return input.body();
    }

    // isJsonBody 用接口默认 true —— 服务调用 body 是 JSON，按参数类型宽容解析

    /**
     * 创建并注册服务地址（执行器调用）。<br>
     * <br>
     * 与 HTTP 通道的 {@code createRoute} 对应：地址与并发参数由执行器从
     * {@code @ServiceEntranceNode}（或退化的 {@code @ApiEntranceNode}）提取后传入。
     *
     * @return 注册成功的路由实例，供执行器调用其并发/响应/关闭方法
     */
    public ServiceEntranceRoute createRoute(String workflowId, AgentNodeDefinition node,
                                            String url, int concurrencyLimit) {
        ServiceEntranceRoute route = new ServiceEntranceRoute(url, workflowId, node, concurrencyLimit);
        registry.register(route);
        return route;
    }
}
