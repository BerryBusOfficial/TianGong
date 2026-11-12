package org.wutong.tiangong.core.function;

import org.wutong.tiangong.common.util.JsonUtil;
import org.wutong.tiangong.llm.api.request.chat.ChatRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具定义 → 协议层的 {@code tools[]}。<br>
 * <br>
 * <h2>为什么外部工具要走原始 schema 而不是 {@code ParamDefinition}</h2>
 * {@code ParamDefinition} 是<b>扁平</b>的（name / type / description / required），
 * 为本地方法的反射调用设计。MCP 的 schema 可以有嵌套对象、{@code oneOf} 分叉、
 * {@code enum} 可选值 —— 压进扁平模型这些信息会全部丢失，模型拿到<b>残缺工具</b>，
 * 不知道该传什么，于是干脆不调、直接编答案。<br>
 * 实测后果：桌宠被问「最近支出流水」时，明明注册了 {@code firefly_query}，
 * 却一个工具都没调，凭空编了 5 条账单出来。<br>
 * 因此：本地函数走 {@code ParamDefinition}（够用），外部工具走原始 schema（完整）。
 */
public final class ToolSchemaConverter {

    /**
     * 能<b>结构化</b>表达的关键字。出现在这之外的（{@code $ref} / {@code allOf} /
     * {@code format} / {@code minimum} …）一律降级进 description ——
     * 宁可啰嗦，也不能让模型拿不到约束。
     */
    private static final Set<String> STRUCTURED_KEYS = Set.of(
            "type", "description", "enum", "items", "properties", "required", "oneOf", "title");

    private ToolSchemaConverter() {
    }

    public static ChatRequest.Tool toTool(AgentFunctionDefinition def) {
        return ChatRequest.Tool.builder()
                .type(ChatRequest.Tool.Type.FUNCTION)
                .function(ChatRequest.Tool.Function.builder()
                        .name(def.getName())                        // 全名（BeanName.函数名）
                        .description(def.getDescription())
                        .parameters(toJsonSchema(def))
                        .build())
                .build();
    }

    private static ChatRequest.JsonSchema toJsonSchema(AgentFunctionDefinition def) {
        Map<String, Object> raw = def.getRawSchema();
        return raw == null || raw.isEmpty()
                ? fromParams(def.getParams())
                : fromRaw(raw);
    }

    // ==================== 外部工具：原始 schema 递归转换 ====================

    @SuppressWarnings("unchecked")
    private static ChatRequest.JsonSchema fromRaw(Map<String, Object> raw) {
        String type = raw.get("type") instanceof String s ? s : "object";

        List<ChatRequest.JsonSchema> oneOf = null;
        if (raw.get("oneOf") instanceof List<?> branches) {
            oneOf = branches.stream()
                    .filter(Map.class::isInstance)
                    .map(b -> fromRaw((Map<String, Object>) b))
                    .toList();
        }

        return ChatRequest.JsonSchema.builder()
                .type(type)
                .properties(propertiesOf(raw))
                .required(stringList(raw.get("required")))
                .oneOf(oneOf)
                .additionalProperties(
                        raw.get("additionalProperties") instanceof Boolean b ? b : null)
                .build();
    }

    /**
     * 抽出 {@code properties} 映射：属性名 → 属性。<br>
     * 嵌套对象复用的也是这一步 —— 子 schema 里装的是「子属性映射」，
     * 不能再套一层完整 schema（那是错的：会变成 {@code properties: {type:object, properties:{}}}）。
     */
    private static Map<String, ChatRequest.JsonSchema.Property> propertiesOf(Map<String, Object> raw) {
        Map<String, ChatRequest.JsonSchema.Property> out = new LinkedHashMap<>();
        if (raw.get("properties") instanceof Map<?, ?> props) {
            for (Map.Entry<?, ?> e : props.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> prop) {
                    out.put(String.valueOf(e.getKey()), fromRawProperty(cast(prop)));
                }
            }
        }
        return out;
    }

    private static ChatRequest.JsonSchema.Property fromRawProperty(Map<String, Object> raw) {
        String type = raw.get("type") instanceof String s ? s : "string";
        String description = raw.get("description") instanceof String s ? s : "";

        // 嵌套对象：子 schema 的 required 属于 object 层级，放在子 schema 自己身上
        ChatRequest.JsonSchema nested = null;
        if ("object".equals(type) && raw.get("properties") instanceof Map<?, ?>) {
            nested = ChatRequest.JsonSchema.builder()
                    .type("object")
                    .properties(propertiesOf(raw))
                    .required(stringList(raw.get("required")))
                    .build();
        }

        ChatRequest.JsonSchema items = null;
        if (raw.get("items") instanceof Map<?, ?> m) {
            items = fromRaw(cast(m));
        }

        return ChatRequest.JsonSchema.Property.builder()
                .type(type)
                .description(appendUnsupported(description, raw))
                .enums(stringList(raw.get("enum")))
                .items(items)
                .properties(nested)
                .build();
    }

    /**
     * 兜底：本框架结构模型表达不了的关键字，以 JSON 原文附加到 description。<br>
     * 丢掉它们等于让模型在缺少约束的情况下猜参数。
     */
    private static String appendUnsupported(String description, Map<String, Object> raw) {
        Map<String, Object> left = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            if (!STRUCTURED_KEYS.contains(e.getKey())) {
                left.put(e.getKey(), e.getValue());
            }
        }
        if (left.isEmpty()) {
            return description;
        }
        String extra;
        try {
            extra = JsonUtil.toJson(left);
        } catch (Exception ex) {
            extra = String.valueOf(left);
        }
        String suffix = "（附加约束 " + extra + "）";
        return description.isBlank() ? suffix.trim() : description + " " + suffix;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return null;
    }

    // ==================== 本地函数：扁平参数 ====================

    private static ChatRequest.JsonSchema fromParams(List<AgentFunctionDefinition.ParamDefinition> params) {
        Map<String, ChatRequest.JsonSchema.Property> properties = new LinkedHashMap<>();
        for (AgentFunctionDefinition.ParamDefinition p : params) {
            properties.put(p.name(), ChatRequest.JsonSchema.Property.builder()
                    .type(p.type())
                    .description(p.description())
                    .build());
        }
        List<String> required = params.stream()
                .filter(AgentFunctionDefinition.ParamDefinition::required)
                .map(AgentFunctionDefinition.ParamDefinition::name)
                .toList();
        return ChatRequest.JsonSchema.builder()
                .properties(properties)
                .required(required)
                .build();
    }
}
