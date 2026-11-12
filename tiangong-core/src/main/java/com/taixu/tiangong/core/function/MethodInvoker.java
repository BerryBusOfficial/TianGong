package com.taixu.tiangong.core.function;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.taixu.tiangong.common.exception.SystemRuntimeException;
import com.taixu.tiangong.common.util.ConvertUtil;
import com.taixu.tiangong.common.util.JsonUtil;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 本地方法工具调用器：包装 @AgentFunction 的 target + method。<br>
 * 按 {@link AgentFunctionDefinition.ParamDefinition} 顺序从 arguments 取参并调用。<br>
 * 缺失的参数若 required=false 且配置了 defaultValue，则按参数类型做转换后填充：
 * 基本类型/包装类用 valueOf，对象用 JsonUtil 反序列化。<br>
 * <b>实参同样会按形参类型转换</b>：协议侧 JSON 数字可能是 Integer / Long / Double，
 * 与形参声明不一致时旧实现直接抛 {@code argument type mismatch}（见下方 {@link #coerce}）。
 */
public class MethodInvoker implements ToolInvoker {

    private final Object target;
    private final Method method;
    private final List<AgentFunctionDefinition.ParamDefinition> params;
    private final Class<?>[] paramTypes;

    public MethodInvoker(Object target, Method method, List<AgentFunctionDefinition.ParamDefinition> params) {
        this.target = target;
        this.method = method;
        this.params = params;
        this.paramTypes = method.getParameterTypes();
        method.setAccessible(true);
    }

    @Override
    public Object invoke(Map<String, Object> arguments) {
        Object[] args = new Object[params.size()];
        for (int i = 0; i < params.size(); i++) {
            AgentFunctionDefinition.ParamDefinition def = params.get(i);
            Object value = arguments.get(def.name());
            if (value == null && !def.required()) {
                value = convertDefaultValue(def.defaultValue(), paramTypes[i], def.name());
            } else {
                value = coerce(value, paramTypes[i], def.name());
            }
            args[i] = value;
        }
        try {
            return method.invoke(target, args);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("工具方法不可访问: " + method.getName(), e);
        } catch (IllegalArgumentException e) {
            // 走到这里说明 coerce 也没救回来（例如形参是某个 POJO 而协议给了标量）。
            // 原样透出 JDK 的 "argument type mismatch" 基本无法定位，这里补上参数名与类型。
            throw new SystemRuntimeException(
                    "工具 [%s] 实参类型不匹配：形参 %s，实参 %s"
                            .formatted(method.getName(),
                                    Arrays.toString(paramTypes),
                                    describe(args)),
                    e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("工具方法执行失败: " + method.getName(), cause);
        }
    }

    private String describe(Object[] args) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(args[i] == null ? "null" : args[i].getClass().getSimpleName());
        }
        return sb.append(']').toString();
    }

    /**
     * 按形参声明类型对<b>实参</b>做转换。<br>
     * <b>为什么必须有</b>：协议侧给的是 JSON，数字可能被解析成 Integer / Long / Double 中任意一种，
     * 而形参写的是 {@code int} 还是 {@code long} 完全由应用决定。旧实现对实参零转换，
     * 于是「形参写 long、模型给了 12（Integer）」这种最常见的组合会抛
     * {@code argument type mismatch}，工具直接失败 —— 且失败原因与业务无关，极难排查。
     */
    private Object coerce(Object value, Class<?> targetType, String paramName) {
        if (value == null || targetType == Object.class) {
            return value;
        }
        try {
            return ConvertUtil.convert(value, targetType);
        } catch (IllegalArgumentException e) {
            throw new SystemRuntimeException(
                    "工具参数 [%s] 的值无法转换为形参类型 [%s]：%s"
                            .formatted(paramName, targetType.getSimpleName(), e.getMessage()),
                    e);
        }
    }

    /**
     * 将 defaultValue 字符串按目标参数类型做类型转换。
     */
    private Object convertDefaultValue(String defaultValue, Class<?> targetType, String paramName) {
        if (defaultValue == null || defaultValue.isBlank()) {
            // 未配置默认值 → null（基本类型调用时会 NPE，但注册期已拦截，此处仅为兜底）
            return null;
        }
        try {
            if (targetType == String.class) {
                return defaultValue;
            }
            if (targetType == int.class || targetType == Integer.class) {
                return Integer.valueOf(defaultValue);
            }
            if (targetType == long.class || targetType == Long.class) {
                return Long.valueOf(defaultValue);
            }
            if (targetType == double.class || targetType == Double.class) {
                return Double.valueOf(defaultValue);
            }
            if (targetType == float.class || targetType == Float.class) {
                return Float.valueOf(defaultValue);
            }
            if (targetType == boolean.class || targetType == Boolean.class) {
                return Boolean.valueOf(defaultValue);
            }
            if (targetType == short.class || targetType == Short.class) {
                return Short.valueOf(defaultValue);
            }
            if (targetType == byte.class || targetType == Byte.class) {
                return Byte.valueOf(defaultValue);
            }
            if (targetType == char.class || targetType == Character.class) {
                return defaultValue.charAt(0);
            }
            // 其他对象类型 → JsonUtil 反序列化
            return JsonUtil.fromJson(defaultValue, targetType);
        } catch (JsonProcessingException e) {
            throw new SystemRuntimeException(
                    "可选参数 [" + paramName + "] 的 defaultValue 无法转换为类型 ["
                            + targetType.getName() + "]: " + defaultValue, e);
        } catch (NumberFormatException e) {
            throw new SystemRuntimeException(
                    "可选参数 [" + paramName + "] 的 defaultValue 无法转换为数值类型 ["
                            + targetType.getName() + "]: " + defaultValue, e);
        }
    }
}
