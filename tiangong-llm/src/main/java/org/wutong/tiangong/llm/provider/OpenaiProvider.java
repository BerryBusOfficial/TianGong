package org.wutong.tiangong.llm.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.wutong.tiangong.common.exception.SystemRuntimeException;
import org.wutong.tiangong.common.type.Embedding;
import org.wutong.tiangong.common.type.impl.Openai;
import org.wutong.tiangong.common.util.JsonUtil;
import org.wutong.tiangong.llm.api.request.Request;
import org.wutong.tiangong.llm.api.request.chat.ChatRequest;
import org.wutong.tiangong.llm.api.request.chat.OpenaiChatRequest;
import org.wutong.tiangong.llm.api.request.embedding.EmbeddingRequest;
import org.wutong.tiangong.llm.api.response.chat.OpenaiChatResponse;
import org.wutong.tiangong.llm.api.response.chat.chunk.OpenaiChatChunkResponse;
import org.wutong.tiangong.llm.api.response.model.OpenaiModelsResponse;
import org.wutong.tiangong.llm.options.chat.OpenaiChatOptions;

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
                .responseFormat(options.getResponseFormat())
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
