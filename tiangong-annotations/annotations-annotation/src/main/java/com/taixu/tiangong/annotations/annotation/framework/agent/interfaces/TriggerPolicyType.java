package com.taixu.tiangong.annotations.annotation.framework.agent.interfaces;

import com.taixu.tiangong.annotations.annotation.framework.agent.TriggerPolicy;

/**
 * 同一 START 节点多次触发时的处理策略。<br>
 * 标注在 {@link TriggerPolicy} 注解上，作用于 {@link StartCondition} 实现类。
 */
public enum TriggerPolicyType {

    /**
     * 中断原运行中的工作流，重新启动。<br>
     * 依赖 {@code AgentContext.cancelFlag} 协作式中断。
     */
    ABORT_AND_RESTART,

    /**
     * 并发启动新工作流，与原工作流并行运行。<br>
     * 受 {@code maxConcurrentWorkflows} 限流约束。
     */
    PARALLEL,

    /**
     * 排队顺延，等原工作流结束后再启动新工作流。<br>
     * 每个 START 节点维护独立队列。
     */
    QUEUE,

    /**
     * 拒绝启动新工作流（默认最安全）。<br>
     * 原工作流运行期间的新触发直接丢弃。
     */
    REJECT,

    /**
     * 自定义策略，实现 {@link TriggerStrategy} 接口。
     */
    CUSTOM
}
