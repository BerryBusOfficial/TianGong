package com.taixu.tiangong.core.function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.taixu.tiangong.common.util.JsonUtil;
import com.taixu.tiangong.llm.api.request.chat.ChatRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具 schema 的下发。<br>
 * <br>
 * 背景：桌宠被问「最近支出流水」时，明明注册了 MCP 的 {@code firefly_query}，
 * 却一个工具都没调，凭空编了 5 条账单。根因是 MCP 的完整 schema 被压进扁平的
 * {@code ParamDefinition} 时，嵌套对象 / {@code oneOf} / {@code enum} 可选值全丢了 ——
 * 模型拿到残缺工具，不知道该传什么，只好编。<br>
 * 这里锁住「完整下发」，尤其 {@code enum} 这个协议字段名（见下面第一个测试）。
 */
class ToolSchemaConverterTest {

    @Test
    @DisplayName("enum 必须序列化成协议字段 \"enum\"，不是 \"enums\"")
    void enumUsesProtocolName() throws Exception {
        Map<String, Object> raw = Map.of(
                "type", "object",
                "properties", Map.of(
                        "operation", Map.of(
                                "type", "string",
                                "description", "操作名",
                                "enum", List.of("list", "get", "delete"))),
                "required", List.of("operation"));

        String json = toJson(raw);

        assertTrue(json.contains("\"enum\""), "协议里这个字段叫 enum，实际输出: " + json);
        assertFalse(json.contains("\"enums\""),
                "Java 字段名是 enums，但没有 @JsonProperty(\"enum\") 就会输出 enums —— 协议不认，"
                        + "可选值等于一个都没下发: " + json);
        assertTrue(json.contains("\"list\""), "可选值本身也要在: " + json);
    }

    @Test
    @DisplayName("嵌套对象要完整下发，不能只留第一层")
    void nestedObjectSurvives() throws Exception {
        Map<String, Object> raw = Map.of(
                "type", "object",
                "properties", Map.of(
                        "filter", Map.of(
                                "type", "object",
                                "description", "过滤条件",
                                "required", List.of("date"),
                                "properties", Map.of(
                                        "date", Map.of("type", "string", "description", "日期"),
                                        "limit", Map.of("type", "integer", "description", "条数")))));

        String json = toJson(raw);

        assertTrue(json.contains("\"limit\""), "第二层的字段要下发: " + json);
        assertTrue(json.contains("\"integer\""), "第二层的类型也要下发: " + json);
    }

    @Test
    @DisplayName("oneOf 分叉要下发 —— firefly_query 就是这种形态")
    void oneOfSurvives() throws Exception {
        Map<String, Object> raw = Map.of(
                "oneOf", List.of(
                        Map.of("type", "object",
                                "properties", Map.of("op", Map.of("type", "string", "enum", List.of("a")))),
                        Map.of("type", "object",
                                "properties", Map.of("op", Map.of("type", "string", "enum", List.of("b"))))));

        String json = toJson(raw);

        assertTrue(json.contains("\"oneOf\""), "组合关键字要下发: " + json);
    }

    @Test
    @DisplayName("表达不了的关键字降级进 description，不能静默丢掉")
    void unsupportedKeywordsFallBackToDescription() throws Exception {
        Map<String, Object> raw = Map.of(
                "type", "object",
                "properties", Map.of(
                        "amount", Map.of(
                                "type", "number",
                                "description", "金额",
                                "minimum", 0,
                                "format", "double")));

        String json = toJson(raw);

        assertTrue(json.contains("minimum") || json.contains("format"),
                "框架结构模型表达不了 minimum / format，至少要降级进 description: " + json);
    }

    @Test
    @DisplayName("本地函数没有原始 schema 时，仍走扁平的 ParamDefinition 路径")
    void localFunctionUsesFlatParams() throws Exception {
        AgentFunctionDefinition def = new AgentFunctionDefinition(
                "set_mood", "PetTool", "切换心情", null, null,
                List.of(new AgentFunctionDefinition.ParamDefinition(
                        "mood", "string", "心情", true, "")));

        String json = JsonUtil.toJson(ToolSchemaConverter.toTool(def));

        assertTrue(json.contains("\"mood\""), "扁平参数照旧下发: " + json);
        assertTrue(json.contains("\"required\""), json);
    }

    /** 用一个带原始 schema 的「外部工具」跑一遍转换，返回序列化结果 */
    private String toJson(Map<String, Object> rawSchema) throws Exception {
        AgentFunctionDefinition def = new AgentFunctionDefinition(
                "firefly_query", "firefly", "查询 Firefly III", null, null,
                List.of(), rawSchema, null);
        ChatRequest.Tool tool = ToolSchemaConverter.toTool(def);
        return JsonUtil.toJson(tool);
    }
}
