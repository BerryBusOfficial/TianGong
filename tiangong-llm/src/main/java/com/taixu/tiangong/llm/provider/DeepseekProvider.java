package com.taixu.tiangong.llm.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.taixu.tiangong.common.exception.SystemRuntimeException;
import com.taixu.tiangong.common.type.Embedding;
import com.taixu.tiangong.common.type.impl.Deepseek;
import com.taixu.tiangong.common.util.JsonUtil;
import com.taixu.tiangong.llm.api.request.Request;
import com.taixu.tiangong.llm.api.request.chat.ChatRequest;
import com.taixu.tiangong.llm.api.request.chat.DeepseekChatRequest;
import com.taixu.tiangong.llm.api.request.embedding.EmbeddingRequest;
import com.taixu.tiangong.llm.api.response.chat.DeepseekChatResponse;
import com.taixu.tiangong.llm.api.response.chat.chunk.DeepseekChatChunkResponse;
import com.taixu.tiangong.llm.api.response.model.DeepseekModelsResponse;
import com.taixu.tiangong.llm.options.chat.DeepseekChatOptions;

public class DeepseekProvider implements LlmProvider<Deepseek> {


    @Override
    public DeepseekChatRequest toChatRequest(ChatRequest request) {
        DeepseekChatOptions options = JsonUtil.convertToBean(request.getChatOptions(), DeepseekChatOptions.class);
        if (options == null) {
            options = DeepseekChatOptions.builder().build();
        }
        // OpenAI 协议（含 DeepSeek 网关）校验 tool_choice 必须伴随 tools：无工具请求
        // （如 json 模式的结构化输出调用）带上它会被 400 拒绝，所以没有 tools 就不下发 tool_choice
        boolean hasTools = request.getTools() != null && !request.getTools().isEmpty();
        // Deepseek 目前只认 json_object（不支持带 schema 的 json_schema）；
        // 需要 schema 约束时由 StructuredOutputRunner 把 schema 注入提示词，框架侧再校验。
        DeepseekChatRequest.ResponseFormat responseFormat = options.getResponseFormat();
        ChatRequest.ResponseFormat structured = request.getResponseFormat();
        if (structured != null && structured.type() != ChatRequest.ResponseFormatType.TEXT) {
            responseFormat = new DeepseekChatRequest.ResponseFormat(
                    DeepseekChatRequest.ResponseFormat.Format.JSON_OBJECT);
        }
        return DeepseekChatRequest.builder()
                .model(request.getModel())
                .messages(request.getMessages())
                .stream(request.getStream())
                .tools(request.getTools())
                .toolChoice(hasTools ? options.getToolChoice() : null)
                .topP(options.getTopP())
                .temperature(options.getTemperature())

                .stop(options.getStop())
                .maxTokens(options.getMaxTokens())
                .reasonEffort(options.getReasonEffort())
                .responseFormat(responseFormat)
                .topLogprobs(options.getTopLogprobs())
                .streamOptions(options.getStreamOptions())
                .userId(options.getUserId())
                .thinking(options.getThinking())

                .build();
    }

    @Override
    public Request<? extends Embedding> toEmbeddingRequest(EmbeddingRequest request) {
        return null;
    }

    @Override
    public DeepseekChatResponse parseChatResponse(String json) {
        try {
            return JsonUtil.fromJson(json, DeepseekChatResponse.class);
        } catch (JsonProcessingException e) {
            throw new SystemRuntimeException("无法反序列化：%s".formatted(e.getMessage()), e);
        }
    }

    @Override
    public DeepseekChatChunkResponse parseChunk(String json) {
        try {
            return JsonUtil.fromJson(json, DeepseekChatChunkResponse.class);
        } catch (JsonProcessingException e) {
            throw new SystemRuntimeException("无法反序列化：%s".formatted(e.getMessage()), e);
        }
    }

    @Override
    public DeepseekModelsResponse parseModelsResponse(String json) {
        try {
            return JsonUtil.fromJson(json, DeepseekModelsResponse.class);
        } catch (JsonProcessingException e) {
            throw new SystemRuntimeException("无法反序列化：%s".formatted(e.getMessage()), e);
        }
    }
}
