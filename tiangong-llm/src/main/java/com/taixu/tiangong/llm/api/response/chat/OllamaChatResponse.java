package com.taixu.tiangong.llm.api.response.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.taixu.tiangong.common.enums.ChatRoleType;
import com.taixu.tiangong.common.function.Arguments;
import com.taixu.tiangong.common.type.impl.Ollama;
import com.taixu.tiangong.llm.api.response.chat.chunk.ChatChunkResponse;

import java.time.LocalDateTime;
import java.util.List;

public record OllamaChatResponse(
        String model,
        LocalDateTime createdAt,
        ResponseMessage message,
        Boolean done,
        String doneReason,
        Long totalDuration,
        Long loadDuration,
        Integer promptEvalCount,
        Long promptEvalDuration,
        Integer promptEvalCachedCount,
        Integer evalCount,
        Long evalDuration,
        List<Logprob> logprobs
) implements ChatResponse<Ollama>, ChatChunkResponse<Ollama> {

    @Override
    public String getContent() {
        return message().content();
    }

    @Override
    public String toJson() throws JsonProcessingException {
        return ChatResponse.super.toJson();
    }

    public record ResponseMessage(
            ChatRoleType role,
            String content,
            String thinking,
            List<ToolCall> toolCalls,
            List<String> images
    ) {
    }

    public record ToolCall(
            String id,
            Function function
    ) {
    }

    public record Function(
            Integer index,
            String name,
            String description,
            Arguments arguments
    ) {
    }

    public record Logprob(
            String token,
            Number logprob,
            Integer[] bytes,
            List<TopLogprob> topLogprobs
    ) {
    }

    public record TopLogprob(
            String token,
            Number logprob,
            Integer[] bytes
    ) {
    }
}
