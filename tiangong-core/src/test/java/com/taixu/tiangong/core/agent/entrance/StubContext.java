package com.taixu.tiangong.core.agent.entrance;

import com.taixu.tiangong.common.agent.context.AgentContext;

/**
 * 入口通道测试用的最小上下文。<br>
 * <br>
 * 通道只用到 workflowId 与取消标志 —— 消息窗口、工具表那些一概不需要，
 * 用真实上下文反而会把"通道"和"上下文"两个被测对象搅在一起。
 */
public class StubContext implements AgentContext {

    private String workflowId;
    private volatile boolean cancelled;

    public StubContext(String workflowId) {
        this.workflowId = workflowId;
    }

    @Override
    public String workflowId() {
        return workflowId;
    }

    @Override
    public void bindWorkflow(String workflowId) {
        this.workflowId = workflowId;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void cancel() {
        cancelled = true;
    }
}
