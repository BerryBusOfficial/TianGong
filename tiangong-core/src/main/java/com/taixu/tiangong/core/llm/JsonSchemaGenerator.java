package com.taixu.tiangong.core.llm;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Java 类型 → JSON Schema（结构化输出契约）。<br>
 * <br>
 * <h2>支持范围</h2>
 * record（首选）、普通 POJO 字段、{@code Optional<T>}、集合、Map、枚举、嵌套对象、
 * 常见时间/UUID 类型。注解识别 {@code @JsonProperty}（名称/required）、
 * {@code @JsonPropertyDescription}、{@code @JsonIgnore}。<br>
 * <br>
 * <b>不支持</b>多态 {@code @JsonSubTypes}、自定义序列化器、{@code @JsonAnyGetter} 这类
 * 无法从类型静态推导的形态 —— 遇到时降级为 {@code object}/{@code array} 的宽松 schema，
 * 而不是假装能生成精确约束（错误的 schema 比宽松 schema 更危险：模型会守着一个假的规则）。
 */
public final class JsonSchemaGenerator {

    private static final Map<Class<?>, Map<String, Object>> CACHE = new ConcurrentHashMap<>();

    private JsonSchemaGenerator() {
    }

    public static Map<String, Object> generate(Class<?> type) {
        if (type == null) {
            throw new IllegalArgumentException("输出类型不能为空");
        }
        return CACHE.computeIfAbsent(type, t -> schemaFor(t, new LinkedHashSet<>()));
    }

    private static Map<String, Object> schemaFor(Type type, Set<Class<?>> stack) {
        if (type == null) {
            return new LinkedHashMap<>();
        }
        if (type instanceof TypeVariable<?> || type instanceof WildcardType) {
            return new LinkedHashMap<>();
        }
        if (type instanceof GenericArrayType genericArray) {
            return arraySchema(schemaFor(genericArray.getGenericComponentType(), stack));
        }
        if (type instanceof ParameterizedType parameterized) {
            return schemaForParameterized(parameterized, stack);
        }
        if (!(type instanceof Class<?> clazz)) {
            return new LinkedHashMap<>();
        }
        if (clazz == Object.class) {
            return new LinkedHashMap<>();
        }
        if (clazz.isArray()) {
            return arraySchema(schemaFor(clazz.getComponentType(), stack));
        }
        if (clazz == String.class || clazz == char.class || clazz == Character.class) {
            return type("string");
        }
        if (clazz == boolean.class || clazz == Boolean.class) {
            return type("boolean");
        }
        if (clazz == byte.class || clazz == Byte.class || clazz == short.class || clazz == Short.class
                || clazz == int.class || clazz == Integer.class
                || clazz == long.class || clazz == Long.class || clazz == BigInteger.class) {
            return type("integer");
        }
        if (clazz == float.class || clazz == Float.class || clazz == double.class || clazz == Double.class
                || clazz == BigDecimal.class) {
            return type("number");
        }
        if (clazz.isEnum()) {
            Map<String, Object> schema = type("string");
            List<String> values = new ArrayList<>();
            for (Object constant : clazz.getEnumConstants()) {
                values.add(((Enum<?>) constant).name());
            }
            schema.put("enum", values);
            return schema;
        }
        if (clazz == LocalDate.class || clazz == LocalDateTime.class || clazz == Instant.class
                || clazz == OffsetDateTime.class || clazz == ZonedDateTime.class || clazz == UUID.class) {
            return type("string");
        }
        if (Collection.class.isAssignableFrom(clazz)) {
            return arraySchema(new LinkedHashMap<>());
        }
        if (Map.class.isAssignableFrom(clazz)) {
            Map<String, Object> schema = type("object");
            schema.put("additionalProperties", true);
            return schema;
        }
        if (stack.contains(clazz)) {
            // 自引用：没有更精确的递归表达；留成宽松对象，避免生成无限 schema
            return type("object");
        }
        stack.add(clazz);
        try {
            return objectSchema(clazz, stack);
        } finally {
            stack.remove(clazz);
        }
    }

    private static Map<String, Object> schemaForParameterized(ParameterizedType parameterized, Set<Class<?>> stack) {
        Type raw = parameterized.getRawType();
        Type[] args = parameterized.getActualTypeArguments();
        if (raw == Optional.class) {
            return schemaFor(args.length > 0 ? args[0] : Object.class, stack);
        }
        if (raw instanceof Class<?> clazz && Collection.class.isAssignableFrom(clazz)) {
            return arraySchema(args.length > 0 ? schemaFor(args[0], stack) : new LinkedHashMap<>());
        }
        if (raw instanceof Class<?> clazz && Map.class.isAssignableFrom(clazz)) {
            Map<String, Object> schema = type("object");
            schema.put("additionalProperties", true);
            return schema;
        }
        return schemaFor(raw, stack);
    }

    private static Map<String, Object> objectSchema(Class<?> clazz, Set<Class<?>> stack) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        RecordComponent[] components = clazz.getRecordComponents();
        if (components != null && components.length > 0) {
            for (RecordComponent component : components) {
                if (component.isAnnotationPresent(JsonIgnore.class)) {
                    continue;
                }
                String name = propertyName(component.getName(), component.getAnnotation(JsonProperty.class));
                Map<String, Object> property = schemaFor(component.getGenericType(), stack);
                applyDescription(property, component.getAnnotation(JsonPropertyDescription.class));
                properties.put(name, property);
                if (isRequired(component.getGenericType(), component.getAnnotation(JsonProperty.class))) {
                    required.add(name);
                }
            }
        } else {
            for (Field field : clazz.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())
                        || Modifier.isTransient(field.getModifiers())
                        || field.isAnnotationPresent(JsonIgnore.class)) {
                    continue;
                }
                String name = propertyName(field.getName(), field.getAnnotation(JsonProperty.class));
                Map<String, Object> property = schemaFor(field.getGenericType(), stack);
                applyDescription(property, field.getAnnotation(JsonPropertyDescription.class));
                properties.put(name, property);
                if (isRequired(field.getGenericType(), field.getAnnotation(JsonProperty.class))) {
                    required.add(name);
                }
            }
            if (properties.isEmpty()) {
                collectGetterProperties(clazz, stack, properties, required);
            }
        }

        Map<String, Object> schema = type("object");
        if (!properties.isEmpty()) {
            schema.put("properties", properties);
            if (!required.isEmpty()) {
                schema.put("required", required);
            }
            schema.put("additionalProperties", false);
        }
        return schema;
    }

    private static void collectGetterProperties(Class<?> clazz, Set<Class<?>> stack,
                                                Map<String, Object> properties, List<String> required) {
        for (Method method : clazz.getMethods()) {
            if (method.getDeclaringClass() == Object.class || Modifier.isStatic(method.getModifiers())
                    || method.getParameterCount() != 0
                    || method.getReturnType() == void.class
                    || method.isAnnotationPresent(JsonIgnore.class)) {
                continue;
            }
            String property = getterName(method);
            if (property == null) {
                continue;
            }
            JsonProperty jsonProperty = method.getAnnotation(JsonProperty.class);
            String name = propertyName(property, jsonProperty);
            Map<String, Object> schema = schemaFor(method.getGenericReturnType(), stack);
            applyDescription(schema, method.getAnnotation(JsonPropertyDescription.class));
            properties.put(name, schema);
            if (isRequired(method.getGenericReturnType(), jsonProperty)) {
                required.add(name);
            }
        }
    }

    private static String getterName(Method method) {
        String name = method.getName();
        if (name.startsWith("get") && name.length() > 3) {
            return decapitalize(name.substring(3));
        }
        if (name.startsWith("is") && name.length() > 2
                && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
            return decapitalize(name.substring(2));
        }
        return null;
    }

    private static String decapitalize(String text) {
        if (text.isEmpty()) {
            return text;
        }
        return Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }

    private static String propertyName(String fallback, JsonProperty annotation) {
        if (annotation != null && !annotation.value().isBlank()) {
            return annotation.value();
        }
        return fallback;
    }

    private static boolean isRequired(Type type, JsonProperty annotation) {
        if (annotation != null && annotation.required()) {
            return true;
        }
        return !(type instanceof ParameterizedType parameterized
                && parameterized.getRawType() == Optional.class);
    }

    private static void applyDescription(Map<String, Object> schema, JsonPropertyDescription description) {
        if (description != null && !description.value().isBlank()) {
            schema.put("description", description.value());
        }
    }

    private static Map<String, Object> type(String type) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", type);
        return schema;
    }

    private static Map<String, Object> arraySchema(Map<String, Object> items) {
        Map<String, Object> schema = type("array");
        schema.put("items", items);
        return schema;
    }
}
