package com.taixu.tiangong.common.util;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;

/**
 * 配置值 / 工具实参 → 目标类型的转换。<br>
 * <b>为什么要有它</b>：框架里有两个地方长期「零类型转换」——
 * <ul>
 *   <li>{@code ValueProcessor}：直接 {@code field.set(instance, 原始值)}。YAML 里的 {@code 12}
 *       是 Integer、注解 defaultValue 却是 String，同一个 {@code @Value} 两种来源两种类型，
 *       字段类型写错就启动期抛 {@code IllegalArgumentException}；键缺失时 defaultValue 还不兜底。</li>
 *   <li>{@code MethodInvoker}：只对 defaultValue 做转换，对实参不做。协议里 JSON 数字可能是
 *       Integer / Long / Double，形参声明不一致就抛 {@code argument type mismatch}。</li>
 * </ul>
 * 两处共用这一份转换，避免各写一遍、各错一半。
 */
public final class ConvertUtil {

    private ConvertUtil() {
    }

    /**
     * 把 value 转成 target 类型。
     *
     * @param value  原始值；null 时返回基本类型的零值 / 引用类型的 null
     * @param target 目标类型（可以是基本类型，如 {@code int.class}）
     * @throws IllegalArgumentException 转换不了（调用方决定是否降级为告警）
     */
    public static Object convert(Object value, Class<?> target) {
        Class<?> type = wrap(target);

        if (value == null) {
            return type.isPrimitive() ? primitiveZero(type) : null;
        }
        if (type == Object.class || type.isAssignableFrom(value.getClass())) {
            return value;
        }

        String text = value.toString().trim();

        if (type == String.class) {
            return value instanceof String ? (String) value : text;
        }
        if (type == Boolean.class) {
            return toBoolean(text);
        }
        if (type == Character.class) {
            if (text.isEmpty()) {
                throw new IllegalArgumentException("无法把空串转为 char");
            }
            return text.charAt(0);
        }
        if (type == BigDecimal.class) {
            return new BigDecimal(text);
        }
        if (type == BigInteger.class) {
            return new BigInteger(text);
        }
        if (type == Duration.class) {
            return toDuration(text);
        }
        if (Number.class.isAssignableFrom(type)) {
            return toNumber(text, type);
        }
        if (type.isEnum()) {
            return toEnum(text, type);
        }
        // 兜底：交给 Jackson（能处理 POJO / 集合 / 嵌套 record）
        try {
            return JsonUtil.convertToBean(value, type);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "无法把 [%s] 转换为 %s".formatted(value, target.getName()), e);
        }
    }

    /**
     * 与 {@link #convert} 相同，但转换失败时返回 {@code fallback} 而不是抛异常。
     * 用于「配错了就退回默认值」这类不该让应用起不来的场景。
     */
    public static Object convertOrDefault(Object value, Class<?> target, Object fallback) {
        try {
            return convert(value, target);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /**
     * 判断某个字段是否「写死成 Object 才能装下配置值」—— 仅用于诊断日志。
     */
    public static boolean needsConversion(Object value, Field field) {
        return value != null && !wrap(field.getType()).isAssignableFrom(value.getClass());
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private static Object primitiveZero(Class<?> boxed) {
        if (boxed == Boolean.class) return false;
        if (boxed == Character.class) return '\0';
        if (boxed == Byte.class) return (byte) 0;
        if (boxed == Short.class) return (short) 0;
        if (boxed == Integer.class) return 0;
        if (boxed == Long.class) return 0L;
        if (boxed == Float.class) return 0.0f;
        if (boxed == Double.class) return 0.0d;
        return null;
    }

    private static Boolean toBoolean(String text) {
        switch (text.toLowerCase()) {
            case "true", "1", "yes", "y", "on" -> {
                return Boolean.TRUE;
            }
            case "false", "0", "no", "n", "off" -> {
                return Boolean.FALSE;
            }
            default -> throw new IllegalArgumentException("无法把 [%s] 转为 boolean".formatted(text));
        }
    }

    private static Object toNumber(String text, Class<?> type) {
        try {
            if (type == Byte.class) return Byte.valueOf(text);
            if (type == Short.class) return Short.valueOf(text);
            if (type == Integer.class) return Integer.valueOf(text);
            if (type == Long.class) return Long.valueOf(text);
            if (type == Float.class) return Float.valueOf(text);
            if (type == Double.class) return Double.valueOf(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("无法把 [%s] 转为 %s".formatted(text, type.getSimpleName()), e);
        }
        throw new IllegalArgumentException("不支持的数值类型: " + type.getName());
    }

    /**
     * 支持纯秒数（{@code 30}）与带单位（{@code 30s} / {@code 5m} / {@code 2h} / {@code 1d}）。
     * YAML 里写 {@code timeout: 30} 表示 30 秒是最常见的直觉。
     */
    private static Duration toDuration(String text) {
        if (!text.isEmpty() && Character.isDigit(text.charAt(0)) && text.matches("\\d+")) {
            return Duration.ofSeconds(Long.parseLong(text));
        }
        return Duration.parse(text.startsWith("P") || text.startsWith("PT")
                ? text
                : "PT" + text.toUpperCase());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object toEnum(String text, Class<?> type) {
        for (Object constant : type.getEnumConstants()) {
            Enum<?> e = (Enum<?>) constant;
            if (e.name().equalsIgnoreCase(text)) {
                return e;
            }
        }
        throw new IllegalArgumentException("枚举 %s 没有值 [%s]".formatted(type.getSimpleName(), text));
    }

}
