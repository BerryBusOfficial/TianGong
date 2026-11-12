package com.taixu.tiangong.annotations.processor.util;

import com.taixu.tiangong.annotations.annotation.Alias;
import com.taixu.tiangong.common.exception.AliasException;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@link Alias} 别名解析工具。<br>
 * 同一注解内成对出现的别名属性（如 value ↔ path）：
 * <ul>
 *     <li>只有一个显式设置 → 另一个取该值</li>
 *     <li>都显式设置且值不同 → 抛 {@link AliasException}</li>
 *     <li>都未设置 → 保持默认值</li>
 * </ul>
 */
public final class AliasUtils {

    private AliasUtils() {
    }

    /**
     * 解析注解属性：应用 @Alias 别名映射，返回所有属性名 → 值的 Map。
     */
    public static Map<String, Object> resolveAttributes(Annotation annotation) {
        Class<? extends Annotation> type = annotation.annotationType();
        Map<String, Object> values = new LinkedHashMap<>();
        for (Method attribute : type.getDeclaredMethods()) {
            values.put(attribute.getName(), invoke(annotation, attribute));
        }
        applyAliases(type, values);
        return values;
    }

    /**
     * 读取指定属性值（应用别名映射）。
     */
    public static Object getAttribute(Annotation annotation, String attributeName) {
        return resolveAttributes(annotation).get(attributeName);
    }

    private static void applyAliases(Class<? extends Annotation> type, Map<String, Object> values) {
        for (Method attribute : type.getDeclaredMethods()) {
            Alias alias = attribute.getAnnotation(Alias.class);
            if (alias == null) {
                continue;
            }
            String a = attribute.getName();
            String b = alias.value();
            if (!values.containsKey(b)) {
                throw new AliasException(
                        "@" + type.getSimpleName() + " 属性 [" + a + "] 的别名 [" + b + "] 不存在");
            }
            Object va = values.get(a);
            Object vb = values.get(b);
            Object da = defaultValue(type, a);
            Object db = defaultValue(type, b);
            boolean aExplicit = !equals(va, da);
            boolean bExplicit = !equals(vb, db);

            if (aExplicit && bExplicit && !equals(va, vb)) {
                throw new AliasException(
                        "@" + type.getSimpleName() + " 别名冲突：[" + a + "] 与 [" + b + "] 都设置了不同的值");
            }
            if (aExplicit) {
                values.put(b, va);
            } else if (bExplicit) {
                values.put(a, vb);
            }
        }
    }

    /** 数组属性按内容比较（注解数组属性的默认值/实例值是不同数组实例，不能引用比较） */
    private static boolean equals(Object a, Object b) {
        if (a != null && b != null && a.getClass().isArray() && b.getClass().isArray()) {
            return Arrays.deepEquals((Object[]) a, (Object[]) b);
        }
        return Objects.equals(a, b);
    }

    private static Object defaultValue(Class<? extends Annotation> type, String attributeName) {
        try {
            return type.getMethod(attributeName).getDefaultValue();
        } catch (NoSuchMethodException e) {
            throw new AliasException("注解属性不存在: " + attributeName, e);
        }
    }

    private static Object invoke(Annotation annotation, Method attribute) {
        try {
            return attribute.invoke(annotation);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new AliasException(
                    "读取 @" + annotation.annotationType().getSimpleName()
                            + " 属性 [" + attribute.getName() + "] 失败", e);
        }
    }
}
