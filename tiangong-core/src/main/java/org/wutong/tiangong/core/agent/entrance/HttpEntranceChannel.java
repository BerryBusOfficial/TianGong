package org.wutong.tiangong.core.agent.entrance;

import lombok.extern.slf4j.Slf4j;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.entrance.ApiEntranceNode;
import org.wutong.tiangong.common.agent.context.AgentContext;
import org.wutong.tiangong.core.agent.AgentNodeDefinition;
import org.wutong.tiangong.core.api.ApiHttpServer;
import org.wutong.tiangong.core.api.entrance.EntranceRequest;
import org.wutong.tiangong.core.api.entrance.EntranceRoute;
import org.wutong.tiangong.core.api.entrance.EntranceRouteRegistry;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * HTTP 入口通道：把 HTTP 入口的"路由注册 + 取请求"收编到 {@link EntranceChannel}。<br>
 * <br>
 * 设计要点：
 * <ul>
 *     <li><b>通道层只负责"取请求"</b>：{@link #await(String, AgentContext)} 通过 workflowId
 *         反查工作流注册的 {@link EntranceRoute}，从其请求队列取下一个请求。
 *         "创建 + 注册路由"作为通道的扩展方法 {@link #createRoute} 暴露给执行器，
 *         因为路由注册需要节点方法 + 注解参数（这些由执行器持有）</li>
 *     <li><b>HTTP 服务器独立于通道</b>：{@link ApiHttpServer} 仍由
 *         {@code ApplicationFrameworkContext} 创建与关闭（不变），本通道不持有它。
         *         {@link #start()} / {@link #close()} 因此是空操作 —— 通道本身没有
 *         "启停"语义</li>
 *     <li><b>协作式取消 + 关闭通过带超时 poll 实现</b>：{@link #await} 内部每
 *         {@link #POLL_MILLIS} 轮询一次 route.pendingRequests，让
 *         {@link AgentContext#isCancelled()} 与路由关闭信号在 ≤ 200ms 内生效。
 *         原 {@code route.take()} 是 {@code BlockingQueue.take()}，阻塞且不可中断，
 *         shutdown / 取消都打不进驻留点 —— 这是本次接入通道顺手修复的隐藏 bug</li>
 *     <li><b>检查点归属执行器</b>：HttpEntranceChannel 不写检查点；A6（fork 不写
 *         检查点）与 A8（等待记录的常驻与关闭）由执行器在调
 *         {@link #await(String, AgentContext)} 前后处理</li>
 * </ul>
 *
 * <h2>使用流程（由 {@code AgentLoopExecutor.handleApiEntrance} 驱动）</h2>
 * <ol>
 *     <li>执行器从 {@code @ApiEntranceNode} 注解读到路由配置（路径 / 方法 / headers /
 *         concurrencyLimit），调 {@link #createRoute} 创建 + 注册 route</li>
 *     <li>执行器写一条 STARTED 等待记录（不变量 A8）</li>
 *     <li>执行器进入驻留循环：{@code while (!cancelled) { req = channel.await(...);
 *         forkProcess(req); }}</li>
 *     <li>驻留循环退出后，执行器调 {@link EntranceRouteRegistry#unregisterWorkflow(String)}
 *         + 完成等待记录</li>
 * </ol>
 */
@Slf4j
public class HttpEntranceChannel implements EntranceChannel<EntranceRequest> {

    /** await 轮询粒度：决定取消/关闭信号的最迟响应延迟 */
    static final long POLL_MILLIS = 200L;

    private final EntranceRouteRegistry routeRegistry;

    public HttpEntranceChannel(EntranceRouteRegistry routeRegistry) {
        this.routeRegistry = routeRegistry;
    }

    @Override
    public String name() {
        return "api";
    }

    /**
     * 空操作 —— HTTP 服务器由 {@code ApplicationFrameworkContext} 独立管理。
     * 通道没有"启停"语义。
     */
    @Override
    public void start() {
    }

    /**
     * 空操作 —— 同 {@link #start()}。HTTP 服务器的关闭不由通道负责。
     */
    @Override
    public void close() {
    }

    /**
     * 阻塞取得下一条 HTTP 请求。<br>
     * <br>
     * 返回 {@code null} 的情形：
     * <ul>
     *     <li>该 workflowId 没有已注册路由（未注册 / 已注销）</li>
     *     <li>路由已关闭（工作流结束 / 取消）</li>
     *     <li>{@link AgentContext#isCancelled()} 为 {@code true}</li>
     * </ul>
     * 返回非 {@code null} 表示取得一条 HTTP 请求，框架 fork 副本在新线程处理。
     */
    @Override
    public EntranceRequest await(String workflowId, AgentContext context) throws InterruptedException {
        List<EntranceRoute> routes = routeRegistry.findByWorkflowId(workflowId);
        if (routes == null || routes.isEmpty()) {
            return null;
        }
        // ⚠ 旧实现是 routes.get(0)：一条工作流注册了多个 @ApiEntranceNode 时，
        // 除第一条以外的路由<b>永远不会被 poll</b> —— 请求照常入队、HTTP 层返回 200 受理，
        // 但没有任何线程取走它，表现为「接口注册了却一直没反应」，且不报任何错。
        // 现在轮流 poll 全部路由，谁先来请求就先处理谁。
        // 代价是取消/关闭信号的最迟响应延迟从 200ms 变成 200ms × 路由数（通常 1~3 条，可接受）。
        while (!context.isCancelled()) {
            boolean allClosed = true;
            for (EntranceRoute route : routes) {
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
        // HTTP body 已是 JSON 字符串（HTTP 层读了字节流后用 UTF-8 转 String）
        return input.body();
    }

    // isJsonBody 用接口默认 true —— HTTP body 是 JSON 字符串，框架会按参数类型解析

    /**
     * 创建并注册 HTTP 路由（执行器调用）。<br>
     * <br>
     * 不是 {@link EntranceChannel} 接口的标准动作 —— HTTP 通道特有的"注册路由"
     * 语义需要节点 + 注解参数，由执行器从 {@code @ApiEntranceNode} 提取后传入。
     *
     * @return 注册成功的路由实例，供执行器调用其 fork / 响应 / 关闭方法
     */
    public EntranceRoute createRoute(String workflowId, AgentNodeDefinition node, ApiEntranceNode ann) {
        EntranceRoute route = new EntranceRoute(
                workflowId,
                ann.route().isEmpty() ? node.name() : ann.route(),
                ann.method(),
                ann.headers(),
                ann.consumes(),
                ann.produces(),
                ann.params(),
                ann.concurrencyLimit(),
                node,
                ann.absolute()
        );
        routeRegistry.register(route);
        return route;
    }
}