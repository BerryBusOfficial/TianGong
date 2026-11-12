package org.wutong.tiangong.core.agent;

import org.wutong.tiangong.common.agent.context.AgentContext;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;

/**
 * Node 异常处理器定义（@NodeExceptionHandler 方法）。<br>
 * 参数约定：第一个参数接收异常（Throwable 兼容），可选第二个 AgentContext 参数；<br>
 * 返回约定：void → 流程结束（返回当前 context）；String → 作为异常跳转的目标节点名。
 */
public class NodeExceptionHandlerDefinition {

    private final List<Class<? extends Exception>> exceptionTypes;
    private final Object target;
    private final Method method;

    public NodeExceptionHandlerDefinition(List<Class<? extends Exception>> exceptionTypes,
                                          Object target, Method method) {
        this.exceptionTypes = exceptionTypes;
        this.target = target;
        this.method = method;
        method.setAccessible(true);
    }

    public List<Class<? extends Exception>> exceptionTypes() {
        return exceptionTypes;
    }

    /** 该处理器是否支持该异常（异常 instanceof 任一声明类型，含继承） */
    public boolean supports(Throwable error) {
        return exceptionTypes.stream().anyMatch(type -> type.isInstance(error));
    }

    /**
     * 执行处理
     *
     * @return null → 流程结束；String → 异常跳转目标节点名
     */
    @SuppressWarnings("unchecked")
    public String handle(Throwable error, Object context) {
        try {
            Parameter[] parameters = method.getParameters();
            Object[] args = new Object[parameters.length];
            for (int i = 0; i < parameters.length; i++) {
                Class<?> type = parameters[i].getType();
                if (Throwable.class.isAssignableFrom(type) && type.isInstance(error)) {
                    args[i] = error;
                } else if (context != null && type.isInstance(context)) {
                    args[i] = context;
                } else {
                    args[i] = null;
                }
            }
            Object result = method.invoke(target, args);
            return result instanceof String s ? s : null;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("异常处理器不可访问: " + method.getName(), e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("异常处理器执行失败: " + method.getName(), cause);
        }
    }

    @Override
    public String toString() {
        return "NodeExceptionHandler[" + method.getDeclaringClass().getSimpleName()
                + "." + method.getName() + " -> " + exceptionTypes + "]";
    }
}
