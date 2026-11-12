package com.taixu.tiangong.core.agent.processor;

import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import com.taixu.tiangong.common.agent.context.AgentContext;
import com.taixu.tiangong.core.agent.AgentNodeRegistry;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 通用节点处理器：处理 START / COMMON / END / ENTRANCE / MERGE。<br>
 * 返回类型：void 或 AgentContext。<br>
 * next 约束：END 不允许 next；其余仅允许 0 或 1 个。<br>
 * <br>
 * <b>EXPORT 不在这里</b>：它的返回值是 HTTP 响应体，类型由应用定，
 * 不能套用「void 或 AgentContext」的约束。见 {@link ExportNodeProcessor}。
 */
public class CommonNodeProcessor extends AbstractNodeProcessor {

    public CommonNodeProcessor(AgentNodeRegistry registry) {
        super(registry);
    }

    @Override
    public boolean support(NodeType type) {
        return type == NodeType.START
                || type == NodeType.COMMON
                || type == NodeType.END
                || type == NodeType.ENTRANCE
                || type == NodeType.MERGE;
    }

    @Override
    public List<Class<?>> supportReturnType() {
        return List.of(void.class, AgentContext.class);
    }

    @Override
    protected void validateNextCount(NodeType type, String[] next, Method method) {
        if (type == NodeType.END) {
            requireNoNext(type, next, method);
        } else {
            requireMaxOneNext(type, next, method);
        }
    }
}
