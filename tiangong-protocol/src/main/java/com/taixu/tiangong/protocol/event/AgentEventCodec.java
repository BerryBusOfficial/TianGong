package com.taixu.tiangong.protocol.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * 事件协议的唯一 JSON 编解码入口。<br>
 * <br>
 * <b>为什么不用 {@code JsonUtil}</b>：框架全局 mapper 是 SNAKE_CASE（给配置绑定用），
 * 而事件协议是对外契约，字段名要稳定成 {@code runId / turnId / retryable}。两套命名
 * 混用会让 SDK 与手写客户端各猜一套，正好是本协议要消灭的问题。
 */
public final class AgentEventCodec {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .build();

    private AgentEventCodec() {
    }

    public static String toJsonString(AgentEventEnvelope envelope) {
        try {
            return MAPPER.writeValueAsString(toJsonNode(envelope));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("事件序列化失败: " + envelope.type(), e);
        }
    }

    public static ObjectNode toJsonNode(AgentEventEnvelope envelope) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("seq", envelope.seq());
        root.put("runId", envelope.runId());
        root.put("turnId", envelope.turnId());
        root.put("timestamp", envelope.timestamp());
        root.put("type", envelope.event().type().wireName());
        root.set("data", MAPPER.valueToTree(envelope.event()));
        return root;
    }

    /**
     * 反序列化。未知事件类型返回 {@code null}（跳过），其余结构错误抛异常 ——
     * 前者是向前兼容，后者是真的协议违约，不能静默吞掉。
     */
    public static AgentEventEnvelope fromJson(String json) {
        try {
            return fromJson(MAPPER.readTree(json));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("事件 JSON 解析失败: " + e.getOriginalMessage(), e);
        }
    }

    public static AgentEventEnvelope fromJson(JsonNode root) {
        if (root == null || root.isNull()) {
            return null;
        }
        long seq = root.path("seq").asLong();
        String runId = root.path("runId").asText("");
        String turnId = root.path("turnId").asText("");
        long timestamp = root.path("timestamp").asLong();
        String wireType = root.path("type").asText("");
        AgentEventType type = AgentEventType.fromWireName(wireType);
        if (type == null) {
            return null;
        }
        JsonNode data = root.path("data");
        AgentEvent event = decodeData(type, data);
        if (event == null) {
            return null;
        }
        return new AgentEventEnvelope(seq, runId, turnId, timestamp, event);
    }

    private static AgentEvent decodeData(AgentEventType type, JsonNode data) {
        try {
            return switch (type) {
                case MESSAGE_DELTA -> new AgentEvent.MessageDelta(data.path("delta").asText(""));
                case REASONING_DELTA -> new AgentEvent.ReasoningDelta(data.path("delta").asText(""));
                case TOOL_CALL -> new AgentEvent.ToolCall(
                        data.path("callId").asText(""),
                        data.path("name").asText(""),
                        data.path("arguments").asText(""));
                case TOOL_RESULT -> new AgentEvent.ToolResult(
                        data.path("callId").asText(""),
                        data.path("name").asText(""),
                        data.path("status").asText(""),
                        data.path("result").asText(""),
                        data.path("durationMs").asLong());
                case CITATION -> new AgentEvent.Citation(
                        data.path("docId").asText(""),
                        data.path("title").asText(""),
                        data.path("url").asText(""),
                        data.path("score").asDouble(),
                        data.path("snippet").asText(""));
                case ARTIFACT -> new AgentEvent.Artifact(
                        data.path("artifactId").asText(""),
                        data.path("kind").asText(""),
                        data.path("name").asText(""),
                        data.path("url").isMissingNode() || data.path("url").isNull()
                                ? null : data.path("url").asText(null),
                        data.path("mimeType").asText(null),
                        data.path("sizeBytes").isNumber() ? data.path("sizeBytes").asLong() : null);
                case USAGE -> new AgentEvent.Usage(
                        data.path("promptTokens").asLong(),
                        data.path("completionTokens").asLong(),
                        data.path("totalTokens").asLong(),
                        data.path("model").asText(null));
                case STRUCTURED -> new AgentEvent.Structured(
                        data.path("schemaName").asText(""),
                        MAPPER.convertValue(data.path("data"), Map.class));
                case ERROR -> new AgentEvent.Error(
                        data.path("code").asText(""),
                        data.path("message").asText(""),
                        data.path("retryable").asBoolean(false));
                case DONE -> new AgentEvent.Done(data.path("status").asText("completed"));
            };
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("事件数据解析失败（type=" + type.wireName() + "）", e);
        }
    }

    /** 供日志/审计查看的扁平数据（不含 seq/runId 等信封字段） */
    public static Map<String, Object> dataOf(AgentEvent event) {
        return MAPPER.convertValue(event, Map.class);
    }
}
