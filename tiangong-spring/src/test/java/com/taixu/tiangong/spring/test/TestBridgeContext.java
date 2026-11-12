package com.taixu.tiangong.spring.test;

import lombok.Getter;
import lombok.Setter;
import com.taixu.tiangong.annotations.annotation.definition.Lifecycle;
import com.taixu.tiangong.annotations.annotation.framework.Component;
import com.taixu.tiangong.annotations.annotation.framework.Scope;
import com.taixu.tiangong.common.agent.context.AgentContext;

/**
 * 集成测试用上下文：PROTOTYPE（每条工作流独立实例，与生产约束一致）。
 */
@Component
@Scope(Lifecycle.PROTOTYPE)
@Getter
@Setter
public class TestBridgeContext implements AgentContext {

    /** 本轮调用携带的数据（入口写入，出口读取） */
    private String note;

    private volatile String workflowId;
    private volatile boolean cancelled;

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
