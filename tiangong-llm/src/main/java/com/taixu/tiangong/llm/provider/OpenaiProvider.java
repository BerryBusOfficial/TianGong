package com.taixu.tiangong.llm.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.taixu.tiangong.common.exception.SystemRuntimeException;
import com.taixu.tiangong.common.type.Embedding;
import com.taixu.tiangong.common.type.impl.Openai;
import com.taixu.tiangong.common.util.JsonUtil;
import com.taixu.tiangong.llm.api.request.Request;
import com.taixu.tiangong.llm.api.request.chat.ChatRequest;
import com.taixu.tiangong.llm.api.request.chat.OpenaiChatRequest;
import com.taixu.tiangong.llm.api.request.embedding.EmbeddingRequest;
import com.taixu.tiangong.llm.api.response.chat.OpenaiChatResponse;
import com.taixu.tiangong.llm.api.response.chat.chunk.OpenaiChatChunkResponse;
import com.taixu.tiangong.llm.api.response.model.OpenaiModelsResponse;
import com.taixu.tiangong.llm.options.chat.OpenaiChatOptions;

/**
 * OpenAI 本家协议适配。<br>
 * 因为本家就是 Deepseek 的母协议，这里的 toChatRequest 与 DeepseekProvider 同构，
 * 差别只在字段集（Deepseek 有 thinking，本家是 reasoning_effort / verbosity）。
 */
public class OpenaiProvider implements LlmProvider<Openai> {

    @Override
    public OpenaiChatRequest toChatRequest(ChatRequest request) {
        OpenaiChatOptions options = JsonUtil.convertToBean(request.getChatOptions(), OpenaiChatOptions.class);
        if (options == null) {
            options = OpenaiChatOptions.builder().build();
        }
        // 同 DeepseekProvider：tool_choice 必须伴随 tools 下发，无工具请求不能带（网关 400）
        boolean hasTools = request.getTools() != null && !request.getTools().isEmpty();
        OpenaiChatRequest.ResponseFormat responseFormat = toResponseFormat(request.getResponseFormat(), options);
        return OpenaiChatRequest.builder()
                .model(request.getModel())
                .messages(request.getMessages())
                .stream(request.getStream())
                .tools(request.getTools())
                .toolChoice(hasTools ? options.getToolChoice() : null)
                .topP(options.getTopP())
                .temperature(options.getTemperature())

                .reasoningEffort(options.getReasoningEffort())
                .verbosity(options.getVerbosity())
                .maxCompletionTokens(options.getMaxCompletionTokens())
                .maxTokens(options.getMaxTokens())
                .stop(options.getStop())
                .responseFormat(responseFormat)
                .streamOptions(options.getStreamOptions())
                .parallelToolCalls(options.getParallelToolCalls())
                .store(options.getStore())
                .logprobs(options.getLogprobs())
                .topLogprobs(options.getTopLogprobs())
                .user(options.getUser())

                .build();
    }

    @Override
    public Request<? extends Embedding> toEmbeddingRequest(EmbeddingRequest request) {
        return null;
    }

    /**
     * provider 无关的结构化输出约束 → OpenAI 的 {@code response_format}。<br>
     * 没有显式约束时沿用配置里的值（既有 json 模式配置不丢）。
     */
    private static OpenaiChatRequest.ResponseFormat toResponseFormat(
            ChatRequest.ResponseFormat structured, OpenaiChatOptions options) {
        if (structured == null) {
            return options.getResponseFormat();
        }
        return switch (structured.type()) {
            case TEXT -> new OpenaiChatRequest.ResponseFormat(
                    OpenaiChatRequest.ResponseFormat.Format.TEXT, null);
            case JSON_OBJECT -> new OpenaiChatRequest.ResponseFormat(
                    OpenaiChatRequest.ResponseFormat.Format.JSON_OBJECT, null);
            case JSON_SCHEMA -> new OpenaiChatRequest.ResponseFormat(
                    OpenaiChatRequest.ResponseFormat.Format.JSON_SCHEMA,
                    new OpenaiChatRequest.ResponseFormat.JsonSchema(
                            structured.name(), structured.strict(), structured.schema()));
        };
    }

    @Override
    public OpenaiChatResponse parseChatResponse(String json) {
        try {
            return JsonUtil.fromJson(json, OpenaiChatResponse.class);
        } catch (JsonProcessingException e) {
            throw new SystemRuntimeException("无法反序列化：%s".formatted(e.getMessage()), e);
        }
    }

    @Override
    public OpenaiChatChunkResponse parseChunk(String json) {
        try {
            return JsonUtil.fromJson(json, OpenaiChatChunkResponse.class);
        } catch (JsonProcessingException e) {
            throw new SystemRuntimeException("无法反序列化：%s".formatted(e.getMessage()), e);
        }
    }

    @Override
    public OpenaiModelsResponse parseModelsResponse(String json) {
        try {
            return JsonUtil.fromJson(json, OpenaiModelsResponse.class);
        } catch (JsonProcessingException e) {
            throw new SystemRuntimeException("无法反序列化：%s".formatted(e.getMessage()), e);
        }
    }
}
