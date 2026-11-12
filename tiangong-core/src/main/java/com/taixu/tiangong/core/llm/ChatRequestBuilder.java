package com.taixu.tiangong.core.llm;

import com.taixu.tiangong.llm.api.request.chat.ChatRequest;
import com.taixu.tiangong.llm.message.Message;

import java.util.List;

/**
 * 绑定指定客户端的 ChatRequest 构建器。
 * model / chatOptions 自动从配置填充，messages / stream 由调用方传入。
 */
public sealed interface ChatRequestBuilder permits DefaultChatRequestBuilder {

    /**
     * stream 由 Provider 兜底
     */
    ChatRequest build(List<Message> messages);

    ChatRequest build(List<Message> messages, ToolParam toolParam);

    ChatRequest build(List<Message> messages, String... toolFunctions);

    /**
     * 显式指定 stream（同步/流式）
     */
    ChatRequest build(List<Message> messages, Boolean stream);

    ChatRequest build(List<Message> messages, Boolean stream, ToolParam toolParam);

    ChatRequest build(List<Message> messages, Boolean stream, String... toolFunctions);

    /**
     * 结构化输出：把目标类型的 JSON Schema 作为 provider 无关约束带上。
     */
    ChatRequest build(List<Message> messages, StructuredOutput output);

    ChatRequest build(List<Message> messages, Boolean stream, ToolParam toolParam, StructuredOutput output);

    /**
     * 便捷入口：由类型直接生成 schema，避免调用方每次手写 {@code StructuredOutput.of(...)}。
     */
    default ChatRequest buildStructured(List<Message> messages, Class<?> outputType) {
        return build(messages, StructuredOutput.of(outputType));
    }

    default ChatRequest buildStructured(List<Message> messages, Boolean stream,
                                        ToolParam toolParam, Class<?> outputType) {
        return build(messages, stream, toolParam, StructuredOutput.of(outputType));
    }

    enum ToolParam {
        ALL,
        NONE
    }

}
