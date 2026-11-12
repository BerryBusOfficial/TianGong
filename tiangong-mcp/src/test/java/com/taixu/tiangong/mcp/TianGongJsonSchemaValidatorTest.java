package com.taixu.tiangong.mcp;

import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自给校验器的行为验证。<br>
 * 两条主线：<b>常见约束要真能拦住</b>，以及 <b>不认识的关键字必须放行</b> ——
 * 后者是刻意的取舍：客户端侧校验宁可漏过，也不要把本来能调通的工具挡在门外。
 */
class TianGongJsonSchemaValidatorTest {

    private final JsonSchemaValidator validator = new TianGongJsonSchemaValidator();

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", properties);
        if (required != null) {
            s.put("required", required);
        }
        return s;
    }

    private static Map<String, Object> prop(String type) {
        return new LinkedHashMap<>(Map.of("type", type));
    }

    @Test
    void catchesMissingRequiredField() {
        var result = validator.validate(
                schema(Map.of("account", prop("string")), List.of("account")),
                Map.of());

        assertFalse(result.valid());
        assertTrue(result.errorMessage().contains("account"), "错误信息要点名缺哪个字段");
    }

    @Test
    void catchesWrongType() {
        var result = validator.validate(
                schema(Map.of("amount", prop("number")), List.of("amount")),
                Map.of("amount", "不是数字"));

        assertFalse(result.valid());
        assertTrue(result.errorMessage().contains("number"));
    }

    @Test
    void catchesValueOutsideEnum() {
        Map<String, Object> status = new LinkedHashMap<>(Map.of("type", "string"));
        status.put("enum", List.of("active", "closed"));

        var result = validator.validate(schema(Map.of("status", status), List.of("status")),
                Map.of("status", "whatever"));

        assertFalse(result.valid());
        assertTrue(result.errorMessage().contains("enum"));
    }

    @Test
    void validatesNestedProperties() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("type", "object");
        inner.put("properties", Map.of("id", prop("integer")));
        inner.put("required", List.of("id"));

        var result = validator.validate(schema(Map.of("filter", inner), List.of("filter")),
                Map.of("filter", Map.of("name", "x")));

        assertFalse(result.valid(), "嵌套对象的必填字段也要查");
        assertTrue(result.errorMessage().contains("filter.id") || result.errorMessage().contains("id"));
    }

    @Test
    void acceptsUnionTypeWhenOneMatches() {
        Map<String, Object> nullable = new LinkedHashMap<>();
        nullable.put("type", List.of("string", "null"));

        assertTrue(validator.validate(schema(Map.of("note", nullable), null),
                Map.of("note", "有值")).valid());
        assertTrue(validator.validate(schema(Map.of("note", nullable), null),
                Map.of()).valid(), "非必填字段缺失不算错");
    }

    @Test
    void ignoresUnknownKeywordsRatherThanBlocking() {
        // pattern / minimum / minLength 我们不实现 —— 不能因为不认识就判失败
        Map<String, Object> strict = new LinkedHashMap<>(Map.of("type", "string"));
        strict.put("pattern", "^[a-z]+$");
        strict.put("minLength", 5);

        var result = validator.validate(schema(Map.of("code", strict), List.of("code")),
                Map.of("code", "AB"));

        assertTrue(result.valid(), "未知关键字一律放行，避免误伤能正常调用的工具");
    }

    @Test
    void emptySchemaAlwaysPasses() {
        assertTrue(validator.validate(Map.of(), Map.of("any", "thing")).valid());
        assertTrue(validator.validate(null, Map.of("any", "thing")).valid());
    }

    @Test
    void doesNotJudgeTheSchemaItself() {
        // server 给的 schema 再怪，也不该由客户端拒绝它
        assertTrue(validator.validateSchema(Map.of("type", "莫名其妙的类型")).valid());
    }
}
