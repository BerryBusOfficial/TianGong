package com.taixu.tiangong.core.agent;

import java.util.List;

/**
 * Node 异常通知定义（@AgentNodeExceptionAdvice 类）。<br>
 * onClass 为空 → 全局捕获；指定 → 仅捕获这些类中节点的异常。
 */
public class NodeExceptionAdviceDefinition {

    private final List<Class<?>> onClasses;
    private final List<NodeExceptionHandlerDefinition> handlers;

    public NodeExceptionAdviceDefinition(List<Class<?>> onClasses,
                                         List<NodeExceptionHandlerDefinition> handlers) {
        this.onClasses = onClasses;
        this.handlers = handlers;
    }

    /** 是否作用域于该节点所属类（onClass 为空 = 全局） */
    public boolean appliesTo(Class<?> nodeOwnerClass) {
        if (onClasses.isEmpty()) {
            return true;
        }
        return onClasses.stream().anyMatch(type -> type.isAssignableFrom(nodeOwnerClass));
    }

    /** 匹配支持该异常的处理器（按声明顺序第一个） */
    public NodeExceptionHandlerDefinition resolveHandler(Throwable error) {
        return handlers.stream()
                .filter(handler -> handler.supports(error))
                .findFirst()
                .orElse(null);
    }

    public List<Class<?>> onClasses() {
        return onClasses;
    }

    public List<NodeExceptionHandlerDefinition> handlers() {
        return handlers;
    }
}
