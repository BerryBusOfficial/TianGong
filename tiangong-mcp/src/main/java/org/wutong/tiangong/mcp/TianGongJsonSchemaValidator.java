package org.wutong.tiangong.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import org.wutong.tiangong.common.util.JsonUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 自给的 JSON Schema 校验器。<br>
 * MCP SDK 2.0 只定义了 {@code JsonSchemaValidator} 接口、<b>不自带实现</b>，
 * 官服也没发对应模块（中央仓库 sdk 目录下没有 mcp-json-schema-validator）。
 * 缺了它 {@code McpClient.build()} 会抛 {@code ServiceConfigurationError} ——
 * 注意那是 <b>Error 不是 Exception</b>，catch 不全会直接把应用带崩。
 *
 * <h2>为什么只实现子集</h2>
 * 这里只做 {@code type / required / properties / items / enum}，其余关键字
 * （pattern、minimum、minLength …）一律放行。<br>
 * 客户端侧校验的目的是挡住「参数明显不对」，不是当网关用。各 MCP server 的 schema
 * 写法差异很大，严格实现只会把本来能调通的工具挡在门外 —— <b>宁可漏过，不要误伤</b>。
 */
public class TianGongJsonSchemaValidator implements JsonSchemaValidator {

    @Override
    public ValidationResponse validate(Map<String, Object> schema, Object value) {
        if (schema == null || schema.isEmpty()) {
            return ValidationResponse.asValid("空 schema，不校验");
        }
        JsonNode node = value instanceof JsonNode jsonNode
                ? jsonNode
                : JsonUtil.getJSON_MAPPER().valueToTree(value);

        List<String> errors = new ArrayList<>();
        check(schema, node, "$", errors);
        return errors.isEmpty()
                ? ValidationResponse.asValid("通过")
                : ValidationResponse.asInvalid(String.join("; ", errors));
    }

    /**
     * schema 自身是否合法：不校验。<br>
     * server 给的 schema 再怪也不该由客户端来拒绝它 —— 那是 server 的责任。
     */
    @Override
    public ValidationResponse validateSchema(Map<String, Object> schema) {
        return ValidationResponse.asValid("不校验 schema 自身");
    }

    private void check(Map<String, Object> schema, JsonNode node, String path, List<String> errors) {
        checkType(schema.get("type"), node, path, errors);

        if (node.isObject()) {
            checkRequired(schema.get("required"), node, path, errors);
            checkProperties(schema.get("properties"), node, path, errors);
        }
        if (node.isArray()) {
            checkItems(schema.get("items"), node, path, errors);
        }
        checkEnum(schema.get("enum"), node, path, errors);
    }

    private void checkType(Object type, JsonNode node, String path, List<String> errors) {
        if (type instanceof String expected) {
            if (!matches(expected, node)) {
                errors.add("%s 期望类型 %s，实际是 %s".formatted(path, expected, describe(node)));
            }
            return;
        }
        // JSON Schema 允许 type 是数组（如 ["string", "null"]）：命中任一即可
        if (type instanceof Collection<?> union && !union.isEmpty()) {
            boolean any = false;
            for (Object candidate : union) {
                if (candidate instanceof String t && matches(t, node)) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                errors.add("%s 不满足任一允许的类型 %s（实际 %s）".formatted(path, union, describe(node)));
            }
        }
    }

    private void checkRequired(Object required, JsonNode node, String path, List<String> errors) {
        if (!(required instanceof Collection<?> fields)) {
            return;
        }
        for (Object field : fields) {
            if (field != null && !node.has(field.toString())) {
                errors.add("%s 缺少必填字段 %s".formatted(path, field));
            }
        }
    }

    private void checkProperties(Object properties, JsonNode node, String path, List<String> errors) {
        if (!(properties instanceof Map<?, ?> props)) {
            return;
        }
        for (Map.Entry<?, ?> entry : props.entrySet()) {
            JsonNode child = node.get(String.valueOf(entry.getKey()));
            if (child == null) {
                continue;                       // 非必填且未提供 → 不校验
            }
            if (entry.getValue() instanceof Map<?, ?> childSchema) {
                check(cast(childSchema), child, path + "." + entry.getKey(), errors);
            }
        }
    }

    private void checkItems(Object items, JsonNode node, String path, List<String> errors) {
        if (items instanceof Map<?, ?> itemSchema) {
            for (JsonNode item : node) {
                check(cast(itemSchema), item, path + "[]", errors);
            }
        }
    }

    private void checkEnum(Object allowed, JsonNode node, String path, List<String> errors) {
        if (!(allowed instanceof Collection<?> values) || values.isEmpty()) {
            return;
        }
        for (Object value : values) {
            if (JsonUtil.getJSON_MAPPER().valueToTree(value).equals(node)) {
                return;
            }
        }
        errors.add("%s 的取值不在 enum %s 内".formatted(path, values));
    }

    private static boolean matches(String type, JsonNode node) {
        return switch (type) {
            case "object" -> node.isObject();
            case "array" -> node.isArray();
            case "string" -> node.isTextual();
            case "number" -> node.isNumber();
            case "integer" -> node.isIntegralNumber();
            case "boolean" -> node.isBoolean();
            case "null" -> node.isNull();
            default -> true;                    // 未知类型名一律放行
        };
    }

    /** 报错里不要把整个值打出来（可能很大），只给类型名 */
    private static String describe(JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isObject()) {
            return "object";
        }
        if (node.isArray()) {
            return "array";
        }
        if (node.isTextual()) {
            return "string";
        }
        if (node.isIntegralNumber()) {
            return "integer";
        }
        if (node.isNumber()) {
            return "number";
        }
        if (node.isBoolean()) {
            return "boolean";
        }
        return node.getNodeType().name().toLowerCase();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }
}
