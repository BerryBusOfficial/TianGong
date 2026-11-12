package com.taixu.tiangong.annotations.annotation.framework.agent.loop.entrance;

import com.taixu.tiangong.annotations.annotation.Alias;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.AgentNode;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeType;

import java.lang.annotation.*;

/**
 * 服务入口节点：工作流执行到此节点时阻塞等待<b>进程内服务调用</b>。<br>
 * <br>
 * 与 {@link ApiEntranceNode} 的区别：不经 HTTP —— 没有 method / headers / consumes
 * 这些协议语义；调用方按 {@link #url()} 寻址（如 {@code agent://quote/price}），
 * 由 {@code ServiceEntranceRegistry} 投递请求，{@code @ServiceExportNode} 回传结果。<br>
 * <br>
 * <b>地址是全局稳定的</b>（不拼 workflowId）：调用方预先知道地址；同一 url 被多个
 * 工作流注册时，调用方必须用 workflow 参数指定（参数形式，不进 url）。<br>
 * <br>
 * 方法签名约定（与 {@code @ApiEntranceNode} 一致）：第一个参数固定为 AgentContext，
 * 后续参数从 JSON body 反序列化（单 DTO 参数走 camelCase / snake_case 宽容解析）。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@AgentNode(type = NodeType.ENTRANCE)
public @interface ServiceEntranceNode {

    @Alias("name")
    String value() default "";

    @Alias("value")
    String name() default "";

    /**
     * 服务地址，形如 {@code agent://quote/price}。必填 —— 没有地址就无法被调用。
     */
    String url();

    /**
     * 并发上限：同时处理的最大调用数，超过返回 503。<b>默认 0 = 不额外限制</b>
     * （与 {@code @ApiEntranceNode.concurrencyLimit} 同一语义：真正的闸门是全局并发槽）。
     */
    int concurrencyLimit() default 0;
}
