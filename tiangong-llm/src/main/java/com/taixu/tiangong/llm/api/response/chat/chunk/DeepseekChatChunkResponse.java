package com.taixu.tiangong.llm.api.response.chat.chunk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.taixu.tiangong.common.type.impl.Deepseek;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DeepseekChatChunkResponse(
        String id,
        String object,
        long created,
        String model,
        String systemFingerprint,
        List<Choice> choices,
        Usage usage) implements ChatChunkResponse<Deepseek> {

    /**
     * ⚠ 末尾的 usage 尾块 {@code choices} 是空数组，直接 {@code getFirst()} 会抛
     * {@code NoSuchElementException} —— 而且是在用户回调里抛，整条流直接失败。<br>
     * 返回 null 而不是抛：内容为空是协议常态，调用方按 null 跳过即可。
     */
    @Override
    public String getContent() {
        if (choices == null || choices.isEmpty() || choices.getFirst().delta() == null) {
            return null;
        }
        return choices.getFirst().delta().content();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(
            int index,
            Delta delta,
            Map<String, Object> logprobs,
            String finishReason
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Delta(
            String role,
            String content,
            String reasoningContent,
            String refusal,
            List<ToolCallDelta> toolCalls,
            List<Object> annotations,
            Object audio
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolCallDelta(
            Integer index,
            String id,
            String type,
            FunctionDelta function
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FunctionDelta(
            String name,
            String arguments
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(
            long promptTokens,
            long completionTokens,
            long totalTokens,
            Map<String, Object> promptTokensDetails,
            Map<String, Object> completionTokensDetails
    ) {
    }

}
