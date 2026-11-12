package org.wutong.tiangong.annotations.annotation.framework.agent.interfaces;

import org.wutong.tiangong.annotations.annotation.framework.agent.TriggerPolicy;

/**
 * 自定义多触发策略：当 {@link TriggerPolicy} 指定 {@link TriggerPolicyType#CUSTOM} 时，
 * 实现此接口决定是否允许新触发启动新工作流。
 */
public interface TriggerStrategy {

    /**
     * 决定本次新触发是否启动新工作流。
     *
     * @param context 当前触发上下文（START 节点名、运行中工作流数等）
     * @return true 则允许启动
     */
    boolean shouldStart(TriggerContext context);
}
