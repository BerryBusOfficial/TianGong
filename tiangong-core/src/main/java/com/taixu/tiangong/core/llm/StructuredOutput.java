package com.taixu.tiangong.core.llm;

import com.taixu.tiangong.common.util.JsonUtil;
import com.taixu.tiangong.llm.api.request.chat.ChatRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 结构化输出契约：目标类型 + 由它生成的 JSON Schema + provider 无关的响应约束。<br>
 * <br>
 * 用法：
 * <pre>{@code
 * StructuredOutput spec = StructuredOutput.of(ReportDraft.class);
 * ChatRequest req = builder.build(messages, spec);
 * ReportDraft draft = StructuredOutputRunner.run(client, req, spec);
 * }</pre>
 */
public record StructuredOutput(String name, Class<?> type, Map<String, Object> schema) {

    public StructuredOutput {
        if (type == null) {
            throw new IllegalArgumentException("结构化输出类型不能为空");
        }
        name = name == null || name.isBlank()
                ? sanitize(type.getSimpleName().isEmpty() ? type.getName() : type.getSimpleName())
                : sanitize(name);
        schema = schema == null
                ? JsonSchemaGenerator.generate(type)
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(schema));
    }

    public static StructuredOutput of(Class<?> type) {
        return new StructuredOutput(null, type, null);
    }

    public ChatRequest.ResponseFormat toResponseFormat() {
        return ChatRequest.ResponseFormat.jsonSchema(name, true, schema);
    }

    public String schemaJson() {
        try {
            return JsonUtil.toJson(schema);
        } catch (Exception e) {
            throw new IllegalStateException("schema 序列化失败", e);
        }
    }

    /**
     * OpenAI 的 schema 名只允许字母/数字/下划线/短横线；嵌套类简单名里的 {@code $}
     * 不合法，统一替换，避免请求在网关侧被 400。
     */
    private static String sanitize(String name) {
        String sanitized = name.replaceAll("[^A-Za-z0-9_-]", "_");
        return sanitized.isBlank() ? "StructuredOutput" : sanitized;
    }
}
