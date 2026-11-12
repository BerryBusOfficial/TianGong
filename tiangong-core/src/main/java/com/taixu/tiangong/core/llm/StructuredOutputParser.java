package com.taixu.tiangong.core.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.taixu.tiangong.common.util.JsonUtil;

import java.util.List;

/**
 * 结构化输出解析：从模型正文里抠出 JSON → 校验 schema → 反序列化为目标类型。<br>
 * <br>
 * <b>为什么允许正文里带前后缀</b>：即使给了 schema，模型仍可能回
 * {@code ```json ... ```}、或者先解释一句再给 JSON。直接 {@code readTree(整段正文)}
 * 会在这些很常见的情况下失败，然后被误判成「模型不守约」。这里先做平衡括号提取，
 * 再做严格校验 —— 宽容的是包装，不是内容。
 */
public final class StructuredOutputParser {

    private StructuredOutputParser() {
    }

    public static <T> T parse(String text, StructuredOutput spec) {
        JsonNode node = parseTree(text, spec);
        try {
            @SuppressWarnings("unchecked")
            T value = (T) JsonUtil.bodyMapper().treeToValue(node, spec.type());
            return value;
        } catch (Exception e) {
            throw new StructuredOutputException(
                    "JSON 与目标类型 [" + spec.type().getName() + "] 不匹配: " + e.getMessage(),
                    List.of(), text, e);
        }
    }

    public static JsonNode parseTree(String text, StructuredOutput spec) {
        String json = extractJson(text);
        if (json == null) {
            throw new StructuredOutputException("正文中找不到完整的 JSON 对象/数组", List.of(), text, null);
        }
        JsonNode node;
        try {
            node = JsonUtil.bodyMapper().readTree(json);
        } catch (Exception e) {
            throw new StructuredOutputException("JSON 语法错误: " + e.getMessage(), List.of(), text, e);
        }
        List<String> errors = JsonSchemaValidator.validate(node, spec.schema());
        if (!errors.isEmpty()) {
            throw new StructuredOutputException("输出不符合 schema: " + String.join("；", errors),
                    errors, text, null);
        }
        return node;
    }

    /**
     * 从任意文本中提取第一个平衡的 JSON 对象或数组；没有则返回 null。<br>
     * 正确处理字符串内的引号/转义与嵌套括号，不会把 {@code "}"} 当成结构闭合。
     */
    public static String extractJson(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        int objectStart = text.indexOf('{');
        int arrayStart = text.indexOf('[');
        int start;
        if (objectStart < 0) {
            start = arrayStart;
        } else if (arrayStart < 0) {
            start = objectStart;
        } else {
            start = Math.min(objectStart, arrayStart);
        }
        if (start < 0) {
            return null;
        }
        char open = text.charAt(start);
        char close = open == '{' ? '}' : ']';
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }
}
