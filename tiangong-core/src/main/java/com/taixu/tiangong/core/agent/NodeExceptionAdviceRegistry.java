package com.taixu.tiangong.core.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * Node 异常通知注册表：管理所有 @AgentNodeExceptionAdvice。<br>
 * 解析顺序：先匹配指定 onClass 的局部通知，再匹配全局通知。
 */
public class NodeExceptionAdviceRegistry {

    private final List<NodeExceptionAdviceDefinition> advices = new ArrayList<>();

    public void register(NodeExceptionAdviceDefinition advice) {
        advices.add(advice);
    }

    /**
     * 解析节点异常的处理器：局部（onClass 匹配）优先，全局兜底。
     *
     * @param nodeOwnerClass 抛出异常的节点方法所属类
     * @return 匹配的处理器；无 → null（异常原样传播）
     */
    public NodeExceptionHandlerDefinition resolve(Class<?> nodeOwnerClass, Throwable error) {
        NodeExceptionHandlerDefinition global = null;
        for (NodeExceptionAdviceDefinition advice : advices) {
            if (!advice.appliesTo(nodeOwnerClass)) {
                continue;
            }
            NodeExceptionHandlerDefinition handler = advice.resolveHandler(error);
            if (handler == null) {
                continue;
            }
            // 局部（指定了 onClass）优先返回；全局（onClass 空）记为兜底
            if (advice.onClasses().isEmpty()) {
                global = handler;
            } else {
                return handler;
            }
        }
        return global;
    }

    public int size() {
        return advices.size();
    }

    public List<NodeExceptionAdviceDefinition> values() {
        return advices;
    }
}
