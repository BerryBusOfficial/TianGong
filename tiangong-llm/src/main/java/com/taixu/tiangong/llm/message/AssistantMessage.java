package com.taixu.tiangong.llm.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.taixu.tiangong.common.enums.ChatRoleType;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AssistantMessage extends Message {

    /**
     * 助手请求的工具调用（OpenAI 协议回放用）——只有「调工具的那一轮助手消息」才非空。<br>
     * <br>
     * <b>回放契约</b>：工具结果消息（{@link ToolMessage}）之前<b>必须</b>有一条带
     * {@code tool_calls} 的助手消息，且 {@code id} 与结果消息的 {@code tool_call_id} 对应 ——
     * 否则 OpenAI / Deepseek 直接 400（"messages with role 'tool' must be a response to a
     * preceding message with 'tool_calls'"）。框架的自动工具循环回放依赖本字段。
     */
    private final List<ToolCall> toolCalls;

    public AssistantMessage(String content) {
        this(content, null, null);
    }

    public AssistantMessage(String content, String name) {
        this(content, name, null);
    }

    public AssistantMessage(String content, String name, List<ToolCall> toolCalls) {
        super(ChatRoleType.ASSISTANT, content, null, name);
        this.toolCalls = toolCalls;
    }

    /**
     * 协议形状的助手工具调用（OpenAI 协议的 {@code tool_calls} 元素）。<br>
     * 请勿改成 record：它要参与 {@code Message} 的多态序列化，普通类与兄弟消息类保持一致。
     */
    @Data
    @AllArgsConstructor
    public static class ToolCall {

        private final String id;
        private final String type;
        private final Function function;

        public record Function(String name, String arguments) {
        }
    }
}
