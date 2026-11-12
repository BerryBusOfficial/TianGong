package com.taixu.tiangong.core.agent.processor;

import com.taixu.tiangong.annotations.annotation.definition.BeanDefinition;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.AgentNode;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeNext;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.ReplayPolicy;
import com.taixu.tiangong.annotations.processor.NodeProcessor;
import com.taixu.tiangong.annotations.processor.util.AliasUtils;
import com.taixu.tiangong.common.exception.SystemRuntimeException;
import com.taixu.tiangong.core.agent.AgentNodeDefinition;
import com.taixu.tiangong.core.agent.AgentNodeRegistry;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * NodeProcessor 抽象基类：封装 @AgentNode 方法扫描、名称解析、返回类型校验、注册通用逻辑。<br>
 * 子类只需声明支持的 NodeType、返回类型、next 数量约束。
 */
public abstract class AbstractNodeProcessor implements NodeProcessor {

    protected final AgentNodeRegistry registry;

    protected AbstractNodeProcessor(AgentNodeRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void process(Object instance, BeanDefinition definition) {
        for (Method method : definition.getBeanClass().getDeclaredMethods()) {
            // 1. 直接标注 @AgentNode
            AgentNode annotation = method.getAnnotation(AgentNode.class);
            Annotation combinedAnnotation = null;

            // 2. 元注解查找：@StdinEntranceNode/@ApiEntranceNode/@ApiExportNode 等组合注解
            if (annotation == null) {
                for (Annotation ann : method.getAnnotations()) {
                    AgentNode meta = ann.annotationType().getAnnotation(AgentNode.class);
                    if (meta != null) {
                        annotation = meta;
                        combinedAnnotation = ann;
                        break;
                    }
                }
            }

            if (annotation == null) {
                continue;
            }
            NodeType type = annotation.type();
            if (!support(type)) {
                continue;
            }
            validateReturnType(method);
            String[] next = resolveNext(method);
            validateNextCount(type, next, method);

            String name = resolveName(annotation, combinedAnnotation, method);
            registry.register(new AgentNodeDefinition(
                    name, type, next, annotation.start(),
                    resolveReplayPolicy(annotation, combinedAnnotation),
                    instance, method));
        }
    }

    /**
     * 解析节点的可重放性声明（检查点契约 A5）。<br>
     * 组合注解（如 @StdinEntranceNode）若自身声明了 {@code replay()}，优先取组合注解的值；
     * 否则回退到 {@code @AgentNode} 的值（即默认 {@code ALWAYS}）。
     */
    protected ReplayPolicy resolveReplayPolicy(AgentNode annotation, Annotation combined) {
        if (combined != null) {
            try {
                Object value = combined.annotationType().getMethod("replay").invoke(combined);
                if (value instanceof ReplayPolicy policy) {
                    return policy;
                }
            } catch (NoSuchMethodException ignored) {
                // 组合注解未声明 replay：回退 @AgentNode 的值
            } catch (ReflectiveOperationException e) {
                throw new SystemRuntimeException(
                        "无法读取组合注解 [" + combined.annotationType().getSimpleName()
                                + "] 的 replay 属性", e);
            }
        }
        return annotation.replay();
    }

    /**
     * 解析节点名。<br>
     * 优先从组合注解（如 @StdinEntranceNode）通过 @Alias 解析 value/name，
     * 无组合注解时从 @AgentNode 自身属性解析，都为空则用方法名。
     */
    protected String resolveName(AgentNode annotation, Annotation combined, Method method) {
        if (combined != null) {
            Map<String, Object> attrs = AliasUtils.resolveAttributes(combined);
            String value = (String) attrs.get("value");
            String name = (String) attrs.get("name");
            String resolved = value != null && !value.isBlank() ? value : name;
            if (resolved != null && !resolved.isBlank()) {
                return resolved;
            }
        }
        // 回退到 @AgentNode 自身属性
        String value = annotation.value();
        String name = annotation.name();
        String resolved = value != null && !value.isBlank() ? value : name;
        return resolved == null || resolved.isBlank() ? method.getName() : resolved;
    }

    /** 解析 @NodeNext 的值数组 */
    protected String[] resolveNext(Method method) {
        NodeNext nodeNext = method.getAnnotation(NodeNext.class);
        return nodeNext != null ? nodeNext.value() : new String[0];
    }

    /** 校验方法返回类型是否在 supportReturnType() 范围内 */
    protected void validateReturnType(Method method) {
        Class<?> returnType = method.getReturnType();
        List<Class<?>> supported = supportReturnType();
        boolean ok = supported.stream().anyMatch(type -> {
            if (type == void.class) {
                return returnType == void.class;
            }
            return type.isAssignableFrom(returnType);
        });
        if (!ok) {
            throw new SystemRuntimeException(
                    "节点方法 [" + method.getDeclaringClass().getSimpleName() + "." + method.getName()
                            + "] 返回类型 [" + returnType.getSimpleName() + "] 不符合约束，仅支持: "
                            + supported.stream().map(Class::getSimpleName).toList());
        }
    }

    /** 校验 @NodeNext 数量是否符合类型约束（子类可重写） */
    protected void validateNextCount(NodeType type, String[] next, Method method) {
        // 默认无约束，子类按类型重写
    }

    /** 便捷：校验 next 必须为空 */
    protected void requireNoNext(NodeType type, String[] next, Method method) {
        if (next.length > 0) {
            throw new SystemRuntimeException(
                    "节点 [" + methodName(method) + "] 类型为 " + type + "，不允许声明 @NodeNext");
        }
    }

    /** 便捷：校验 next 只能 0 或 1 个 */
    protected void requireMaxOneNext(NodeType type, String[] next, Method method) {
        if (next.length > 1) {
            throw new SystemRuntimeException(
                    "节点 [" + methodName(method) + "] 类型为 " + type
                            + "，@NodeNext 仅允许 0 或 1 个，实际 " + next.length + " 个");
        }
    }

    /** 便捷：校验 next 必须 ≥ 2 个 */
    protected void requireMinTwoNext(NodeType type, String[] next, Method method) {
        if (next.length < 2) {
            throw new SystemRuntimeException(
                    "节点 [" + methodName(method) + "] 类型为 " + type
                            + "，@NodeNext 必须 ≥ 2 个，实际 " + next.length + " 个");
        }
    }

    private String methodName(Method method) {
        return method.getDeclaringClass().getSimpleName() + "." + method.getName();
    }
}
