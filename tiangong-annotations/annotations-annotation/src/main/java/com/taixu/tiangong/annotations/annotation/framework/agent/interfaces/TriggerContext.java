package com.taixu.tiangong.annotations.annotation.framework.agent.interfaces;

/**
 * 触发策略决策上下文，传递给 {@link TriggerStrategy#shouldStart}。
 */
public interface TriggerContext {

    /** 触发的 START 节点名 */
    String startNodeName();

    /** 该 START 节点当前运行中的工作流数 */
    int activeCount();

    /** 框架全局运行中的工作流总数 */
    int globalActiveCount();

    /** 该 START 节点的最大并发工作流数（配置值） */
    int maxConcurrentPerStart();
}
