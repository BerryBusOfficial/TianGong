package com.taixu.tiangong.annotations.annotation.framework.agent.loop.entrance;

import com.taixu.tiangong.annotations.annotation.Alias;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.AgentNode;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeType;

import java.lang.annotation.*;

/**
 * 审批入口节点：工作流执行到此节点时阻塞等待人工审批结果。<br>
 * <br>
 * <h2>语义</h2>
 * 与 {@code @StdinEntranceNode}（会话循环）和 {@code @ApiEntranceNode}（HTTP fork）
 * 不同，{@code @ApproveNode} 是<b>一次性等待</b>（{@code WAIT_ONCE} 模式）：
 * 阻塞取一次审批决定就继续主流程，<b>不会再次回到本节点</b>。<br>
 * <br>
 * <h2>方法签名约定</h2>
 * <pre>
 * &#64;ApproveNode(channel = "approval", value = "qa-review")
 * void qaReview(AgentContext ctx, ApprovalDecision decision) { ... }
 * </pre>
 * 第一个参数固定为 AgentContext，第二个参数从审批通道的 JSON 反序列化。
 *
 * <h2>答案怎么用 —— 框架不管</h2>
 * 按"契约边界是单个节点"原则：{@code @ApproveNode} 只负责"取一次输入"。
 * 拿到审批决定写进上下文后，怎么用由后续节点（或塞给 LLM 让 Agent 自己判断）
 * 决定。框架不保证 BRANCH 节点的封闭集合能解析审批结果 —— 这是用户的图设计责任。
 *
 * <h2>通道标识</h2>
 * {@link #channel()} 按名引用框架注册的 {@code EntranceChannel} 实现（默认
 * "approval"）。用户可在启动时替换为自己的通道实现（如邮件审批 / IM 审批 / 钉钉审批）。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@AgentNode(type = NodeType.ENTRANCE)
public @interface ApproveNode {

    @Alias("name")
    String value() default "";

    @Alias("value")
    String name() default "";

    /**
     * 入口通道名，引用框架注册的 {@code EntranceChannel} 实现。
     * 默认 "approval"（{@code ApprovalEntranceChannel}）。
     */
    String channel() default "approval";
}