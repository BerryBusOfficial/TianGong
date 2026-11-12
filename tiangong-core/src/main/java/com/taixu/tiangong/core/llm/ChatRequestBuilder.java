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

    enum ToolParam {
        ALL,
        NONE
    }

}
