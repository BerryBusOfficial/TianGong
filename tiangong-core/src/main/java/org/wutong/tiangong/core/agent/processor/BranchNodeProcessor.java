package org.wutong.tiangong.core.agent.processor;

import org.wutong.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import org.wutong.tiangong.core.agent.AgentNodeRegistry;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 分支节点处理器：处理 BRANCH。<br>
 * 返回类型：String（返回值为下一个节点名，必须在 @NodeNext 声明范围内）。<br>
 * next 约束：必须 ≥ 2 个。
 */
public class BranchNodeProcessor extends AbstractNodeProcessor {

    public BranchNodeProcessor(AgentNodeRegistry registry) {
        super(registry);
    }

    @Override
    public boolean support(NodeType type) {
        return type == NodeType.BRANCH;
    }

    @Override
    public List<Class<?>> supportReturnType() {
        return List.of(String.class);
    }

    @Override
    protected void validateNextCount(NodeType type, String[] next, Method method) {
        requireMinTwoNext(type, next, method);
    }
}
