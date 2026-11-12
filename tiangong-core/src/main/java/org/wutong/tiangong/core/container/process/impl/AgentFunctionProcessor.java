package org.wutong.tiangong.core.container.process.impl;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.wutong.tiangong.annotations.annotation.definition.BeanDefinition;
import org.wutong.tiangong.annotations.annotation.framework.agent.AgentFunction;
import org.wutong.tiangong.common.exception.SystemRuntimeException;
import org.wutong.tiangong.core.container.process.AnnotationProcessor;
import org.wutong.tiangong.core.function.AgentFunctionDefinition;
import org.wutong.tiangong.core.function.FunctionRegistry;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

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
            registry.register(new AgentFunctionDefinition(
                    functionName,
                    definition.getBeanName(),     // ← 新增 beanName
                    ann.description(), instance, method, resolveParams(method)));
        }
    }

    private List<AgentFunctionDefinition.ParamDefinition> resolveParams(Method method) {
        List<AgentFunctionDefinition.ParamDefinition> defs = new ArrayList<>();
        for (Parameter p : method.getParameters()) {
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
