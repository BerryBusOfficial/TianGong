package com.taixu.tiangong.core.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 最小 JSON Schema 校验器：只实现本框架生成器会产出的关键字。<br>
 * <br>
 * <b>为什么不用第三方 validator</b>：结构化输出的 schema 由
 * {@link JsonSchemaGenerator} 生成，关键字集合是封闭的；引入完整 JSON Schema 引擎
 * 会带来版本/依赖负担，而本项目当前只需要「类型、必填、枚举、数组元素、嵌套对象、
 * 额外字段」这几条。遇到不认识的 schema 关键字就跳过（宽松），不假装校验了它。<br>
 * <br>
 * 错误信息带路径（{@code $.items[0].title}），修复提示词因此能精确指向问题字段，
 * 不必让模型猜哪里错了。
 */
public final class JsonSchemaValidator {

    private JsonSchemaValidator() {
    }

    public static List<String> validate(JsonNode value, Map<String, Object> schema) {
        List<String> errors = new ArrayList<>();
        validateNode(value, schema, "$", errors);
        return errors;
    }

    @SuppressWarnings("unchecked")
    private static void validateNode(JsonNode value, Map<String, Object> schema,
                                     String path, List<String> errors) {
        if (schema == null || schema.isEmpty()) {
            return;
        }
        if (schema.get("oneOf") instanceof List<?> branches) {
            validateOneOf(value, (List<Map<String, Object>>) branches, path, errors);
            return;
        }
        String type = schema.get("type") instanceof String s ? s : null;
        if (type != null && !typeMatches(value, type)) {
            errors.add(path + " 期望 " + type + "，实际是 " + describe(value));
            return;
        }
        if (value == null || value.isNull()) {
            return;
        }
        if (schema.get("enum") instanceof List<?> allowed) {
            boolean matched = false;
            for (Object candidate : allowed) {
                if (candidate != null && value.isTextual()
                        && candidate.toString().equals(value.asText())) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                errors.add(path + " 的值不在枚举内: " + value);
            }
        }
        if (value.isObject()) {
            validateObject(value, schema, path, errors);
        } else if (value.isArray() && schema.get("items") instanceof Map<?, ?> items) {
            for (int i = 0; i < value.size(); i++) {
                validateNode(value.get(i), (Map<String, Object>) items, path + "[" + i + "]", errors);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void validateObject(JsonNode value, Map<String, Object> schema,
                                       String path, List<String> errors) {
        Map<String, Object> properties = schema.get("properties") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        if (schema.get("required") instanceof List<?> required) {
            for (Object field : required) {
                String name = String.valueOf(field);
                if (!value.has(name) || value.get(name).isNull()) {
                    errors.add(path + " 缺少必填字段: " + name);
                }
            }
        }
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            if (value.has(entry.getKey()) && entry.getValue() instanceof Map<?, ?> propertySchema) {
                validateNode(value.get(entry.getKey()), (Map<String, Object>) propertySchema,
                        path + "." + entry.getKey(), errors);
            }
        }
        if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
            value.fieldNames().forEachRemaining(name -> {
                if (!properties.containsKey(name)) {
                    errors.add(path + " 出现未声明字段: " + name);
                }
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static void validateOneOf(JsonNode value, List<Map<String, Object>> branches,
                                      String path, List<String> errors) {
        for (Map<String, Object> branch : branches) {
            List<String> branchErrors = new ArrayList<>();
            validateNode(value, branch, path, branchErrors);
            if (branchErrors.isEmpty()) {
                return;
            }
        }
        errors.add(path + " 不匹配 oneOf 的任何一个分支");
    }

    private static boolean typeMatches(JsonNode value, String type) {
        if (value == null) {
            return false;
        }
        return switch (type) {
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "null" -> value.isNull();
            default -> true;
        };
    }

    private static String describe(JsonNode value) {
        if (value == null) {
            return "null";
        }
        return value.getNodeType().name().toLowerCase();
    }
}
