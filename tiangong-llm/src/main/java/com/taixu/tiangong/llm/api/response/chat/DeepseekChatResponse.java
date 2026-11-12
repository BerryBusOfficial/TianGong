package com.taixu.tiangong.llm.api.response.chat;

import com.taixu.tiangong.common.type.impl.Deepseek;

import java.util.List;
import java.util.Map;

public record DeepseekChatResponse(
        String id,
        String object,
        long created,
        String model,
        List<Choice> choices,
        String systemFingerprint,
        Usage usage,
        String serviceTier,
        // 网关（OpenAI 兼容代理）会回 request_id 作排障追踪号；不建模则每次反序列化都触发
        // 「未知字段被忽略」WARN，噪音会淹没真正的告警
        String requestId
) implements ChatResponse<Deepseek> {

    @Override
    public String getContent() {
        return choices().getFirst().message().content();
    }

    public record Usage(
            long promptTokens,
            long completionTokens,
            long totalTokens,
            Map<String, Object> promptTokensDetails,
            Map<String, Object> completionTokensDetails
    ) {
    }

    public record Choice(
            int index,
            ResponseMessage message,
            Map<String, Object> logprobs,
            String finishReason
    ) {
    }

    public record ResponseMessage(
            String role,
            String content,
            String reasoningContent,
            String refusal,
            List<ToolCall> toolCalls,
            List<Object> annotations,
            Object audio) {
    }

    public record ToolCall(
            String id,
            String type,
            Function function
    ) {
    }

    public record Function(
            String name,
            String arguments
    ) {
    }

}
