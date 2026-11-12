package org.wutong.tiangong.llm.api.response.chat;

import org.wutong.tiangong.common.type.impl.Openai;

import java.util.List;
import java.util.Map;

/**
 * OpenAI 本家非流式响应。<br>
 * 推理模型（o 系列 / gpt-5）的思考内容<b>不会</b>出现在 chat.completions 响应里，
 * 所以这里没有 reasoning 字段 —— 想要思考过程只能走 Responses API。
 */
public record OpenaiChatResponse(
        String id,
        String object,
        long created,
        String model,
        List<Choice> choices,
        String systemFingerprint,
        Usage usage,
        String serviceTier
) implements ChatResponse<Openai> {

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
            String refusal,
            List<ToolCall> toolCalls,
            List<Object> annotations,
            Object audio
    ) {
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
