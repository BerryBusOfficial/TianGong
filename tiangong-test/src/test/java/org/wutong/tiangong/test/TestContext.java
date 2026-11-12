package org.wutong.tiangong.test;

import org.wutong.tiangong.common.agent.context.AgentContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试用上下文：记录节点执行轨迹，便于断言工作流路径。<br>
 * 所有手动装配的节点测试均使用此上下文。
 */
public class TestContext implements AgentContext {

    /** 记录经过的节点名（按执行顺序） */
    public final List<String> steps = new ArrayList<>();

    /** 通用状态载体：测试用条件判断 */
    public String flag = "";

    /** 分支选择控制：非空走 B 路径 */
    public String target = "";

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
        this.cancelled = true;
    }
}
