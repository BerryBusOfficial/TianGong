package com.taixu.tiangong.core.agent.processor;

import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import com.taixu.tiangong.common.agent.context.AgentContext;
import com.taixu.tiangong.core.agent.AgentNodeRegistry;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 并行节点处理器：处理 PARALLEL。<br>
 * 返回类型：void 或 AgentContext。<br>
 * next 约束：必须 ≥ 2 个（并发分支）。
 */
public class ParallelNodeProcessor extends AbstractNodeProcessor {

    public ParallelNodeProcessor(AgentNodeRegistry registry) {
        super(registry);
    }

    @Override
    public boolean support(NodeType type) {
        return type == NodeType.PARALLEL;
    }

    @Override
    public List<Class<?>> supportReturnType() {
        return List.of(void.class, AgentContext.class);
    }

    @Override
    protected void validateNextCount(NodeType type, String[] next, Method method) {
        requireMinTwoNext(type, next, method);
    }
}
