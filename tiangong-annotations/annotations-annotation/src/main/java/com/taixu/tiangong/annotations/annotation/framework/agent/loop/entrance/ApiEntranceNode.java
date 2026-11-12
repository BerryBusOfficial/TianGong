package com.taixu.tiangong.annotations.annotation.framework.agent.loop.entrance;

import com.taixu.tiangong.annotations.annotation.Alias;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.AgentNode;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import com.taixu.tiangong.common.enums.RequestMethod;

import java.lang.annotation.*;

/**
 * API 入口节点：工作流执行到此节点时阻塞等待 HTTP 请求。<br>
 * <br>
 * 路由注册时机：工作流执行到本节点时动态注册，路由路径为
 * {@code /{workflow_id}/{route()}}。<br>
 * <br>
 * 并发模型：收到请求后 fork 上下文副本继续执行后续节点。<br>
 * 并发上限由 {@link #concurrencyLimit()} 控制，超过则返回 429。<br>
 * <br>
 * 方法签名约定：<br>
 * <pre>
 * &#64;ApiEntranceNode(route = "/submit", method = RequestMethod.POST)
 * void submit(AgentContext ctx, MyRequest req) { ... }
 * </pre>
 * 第一个参数固定为 AgentContext，后续参数从 JSON body 反序列化。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@AgentNode(type = NodeType.ENTRANCE)
public @interface ApiEntranceNode {

    @Alias("name")
    String value() default "";

    @Alias("value")
    String name() default "";

    /**
     * 路由地址，完整路由为 {@code /{workflow_id}/{route()}}。
     */
    String route() default "";

    /**
     * 把路由锚定到 {@code /{route()}}，不带 workflowId 前缀。默认 {@code false}。<br>
     * <br>
     * <b>为什么需要它</b>：默认路径里的 workflowId 是运行期生成的随机串、每次启动都不同，
     * 客户端没法预先知道 URL，只能先探测再调用 —— 回调 / Webhook 这类「地址要提前配给
     * 外部系统」的场景根本没法用。置为 {@code true} 后路径稳定可配。<br>
     * <br>
     * 代价：同一进程内多个工作流的 absolute 路由若重名会互相抢占，
     * 注册时检测到冲突会直接报错（不会静默覆盖）。
     */
    boolean absolute() default false;

    /**
     * 允许的请求方法，默认 GET。
     */
    RequestMethod[] method() default RequestMethod.GET;

    /**
     * 要求的请求头（如 {@code "Authorization"}），不满足返回 400。
     */
    String[] headers() default {};

    /**
     * 接受的 Content-Type（如 {@code "application/json"}），不匹配返回 415。
     */
    String[] consumes() default {};

    /**
     * 响应 Content-Type（由 ExportNode 使用），默认 application/json。
     */
    String[] produces() default {};

    /**
     * 要求的查询参数名（如 {@code "page"}），缺失返回 400。
     */
    String[] params() default {};

    /**
     * 并发上限：同时处理的最大请求数，超过返回 429。<b>默认 0 = 不额外限制</b>。<br>
     * <br>
     * <h2>为什么默认放开</h2>
     * 这个默认值曾经是 1（串行），代价是一个隐蔽的第二道闸门：
     * <ul>
     *     <li>HTTP 入口的真正闸门是<b>全局并发槽</b>（{@code WorkflowScheduler}，默认 16）——
     *         那才是保护进程的那道限流</li>
     *     <li>而这里的 1 只在第 2 个并发请求到达时才以 429 暴露出来，
     *         单请求测试一切正常 → 上线后突发流量全被打回，且很难联想到是这个默认值</li>
     *     <li>长任务（审查、批处理）尤其致命：一个请求跑几分钟，其余全部 429</li>
     * </ul>
     * 需要保护下游（如限流昂贵的 LLM 调用）时请<b>显式</b>设置，别依赖默认。
     */
    int concurrencyLimit() default 0;

}
