package com.taixu.tiangong.core.container.process.impl;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import com.taixu.tiangong.annotations.annotation.definition.BeanDefinition;
import com.taixu.tiangong.annotations.annotation.framework.agent.AgentFunction;
import com.taixu.tiangong.annotations.annotation.framework.agent.AgentTool;
import com.taixu.tiangong.common.agent.call.AgentCallContext;
import com.taixu.tiangong.common.exception.SystemRuntimeException;
import com.taixu.tiangong.core.container.process.AnnotationProcessor;
import com.taixu.tiangong.core.function.AgentFunctionDefinition;
import com.taixu.tiangong.core.function.FunctionRegistry;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Data
@RequiredArgsConstructor
public class AgentFunctionProcessor implements AnnotationProcessor {

    private final FunctionRegistry registry;

    @Override
    public void process(Object instance, BeanDefinition definition) throws Exception {
        for (Method method : definition.getBeanClass().getDeclaredMethods()) {   // ← instance.getClass() → getBeanClass()（防 ByteBuddy 增强丢注解）
            AgentFunction ann = method.getAnnotation(AgentFunction.class);
            if (ann == null) continue;
            String functionName = ann.name().isBlank() ? method.getName() : ann.name();
            List<AgentFunctionDefinition.ParamDefinition> params = resolveParams(method);
            registry.register(new AgentFunctionDefinition(
                    functionName,
                    definition.getBeanName(),     // ← 新增 beanName
                    ann.description(), instance, method, params,
                    mergeScopes(definition.getBeanClass(), ann),
                    ann.requiresApproval()));
        }
    }

    /**
     * 类级 {@code @AgentTool(scopes)} 与方法级 {@code @AgentFunction(scopes)} 取并集。<br>
     * 方法级不能覆盖类级：覆盖会让一个方法悄悄放宽整类工具的安全边界。
     */
    private static Set<String> mergeScopes(Class<?> beanClass, AgentFunction ann) {
        Set<String> scopes = new LinkedHashSet<>();
        AgentTool toolAnn = beanClass.getAnnotation(AgentTool.class);
        if (toolAnn != null) {
            addScopes(scopes, toolAnn.scopes());
        }
        addScopes(scopes, ann.scopes());
        return scopes;
    }

    private static void addScopes(Set<String> target, String[] scopes) {
        if (scopes == null) {
            return;
        }
        for (String scope : scopes) {
            if (scope != null && !scope.isBlank()) {
                target.add(scope.trim());
            }
        }
    }

    private List<AgentFunctionDefinition.ParamDefinition> resolveParams(Method method) {
        List<AgentFunctionDefinition.ParamDefinition> defs = new ArrayList<>();
        for (Parameter p : method.getParameters()) {
            // 调用者上下文由框架注入，不是模型可填的工具参数：不进 schema、不进 args 列表。
            if (p.getType() == AgentCallContext.class) {
                continue;
            }
            AgentFunction.ToolParam tp = p.getAnnotation(AgentFunction.ToolParam.class);
            boolean required = tp == null || tp.required();
            String defaultValue = tp != null && !required ? tp.defaultValue() : "";
            // required=false 时必须提供 defaultValue，否则反射调用基本类型会 NPE
            if (!required && defaultValue.isBlank()) {
                throw new SystemRuntimeException(
                        "可选参数 [" + p.getName() + "] 未设置 defaultValue（required=false 时必须提供）",
                        new IllegalArgumentException());
            }
            defs.add(new AgentFunctionDefinition.ParamDefinition(
                    tp != null && !tp.name().isBlank() ? tp.name() : p.getName(),
                    toSchemaType(p.getType()),
                    tp != null ? tp.description() : "",
                    required,
                    defaultValue
            ));
        }
        return defs;
    }

    private String toSchemaType(Class<?> type) {
        if (type == String.class) return "string";
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class) return "integer";
        if (type == double.class || type == Double.class || type == float.class || type == Float.class) return "number";
        if (type == boolean.class || type == Boolean.class) return "boolean";
        if (type.isArray() || Collection.class.isAssignableFrom(type)) return "array";
        return "object";
    }
}
