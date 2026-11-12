package org.wutong.tiangong.llm.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.wutong.tiangong.common.exception.SystemRuntimeException;
import org.wutong.tiangong.common.type.impl.Ollama;
import org.wutong.tiangong.common.util.JsonUtil;
import org.wutong.tiangong.llm.api.request.chat.ChatRequest;
import org.wutong.tiangong.llm.api.request.chat.OllamaChatRequest;
import org.wutong.tiangong.llm.api.request.embedding.EmbeddingRequest;
import org.wutong.tiangong.llm.api.request.embedding.OllamaEmbeddingRequest;
import org.wutong.tiangong.llm.api.response.chat.OllamaChatResponse;
import org.wutong.tiangong.llm.api.response.embed.OllamaEmbeddingResponse;
import org.wutong.tiangong.llm.api.response.model.OllamaModelsResponse;
import org.wutong.tiangong.llm.options.chat.OllamaChatOption;
import org.wutong.tiangong.llm.options.embedding.OllamaEmbeddingOptions;

public class OllamaProvider implements LlmProvider<Ollama> {

    @Override
    public OllamaChatRequest toChatRequest(ChatRequest request) {
        OllamaChatOption option = JsonUtil.convertToBean(request.getChatOptions(), OllamaChatOption.class);
        if (option == null) {
            option = OllamaChatOption.builder().build();   // 默认空 options
        }
        return OllamaChatRequest.builder()
                .model(request.getModel())
                .messages(request.getMessages())
                .tools(request.getTools())
                .stream(request.getStream())

                .format(option.getFormat())
                .options(option.getOptions())
                .think(option.getThink())
                .keepAlive(option.getKeepAlive())
                .logprobs(option.getLogprobs())
                .topLogprobs(option.getTopLogprobs())

                .build();
    }

    @Override
    public OllamaEmbeddingRequest toEmbeddingRequest(EmbeddingRequest request) {
        OllamaEmbeddingOptions options = JsonUtil.convertToBean(request.getOption(), OllamaEmbeddingOptions.class);
        if (options == null) {
            options = OllamaEmbeddingOptions.builder().build();   // 默认空 options
        }
        return OllamaEmbeddingRequest.builder()
                .model(request.getModel())
                .input(request.getInput())

                .truncate(options.getTruncate())
                .dimensions(options.getDimensions())
                .keepAlive(options.getKeepAlive())

                .build();
    }

    @Override
    public OllamaChatResponse parseChatResponse(String json) {
        try {
            return JsonUtil.fromJson(json, OllamaChatResponse.class);
        } catch (JsonProcessingException e) {
            throw new SystemRuntimeException("解析 Ollama 响应失败", e);
        }
    }

    @Override
    public OllamaChatResponse parseChunk(String json) {
        try {
            return JsonUtil.fromJson(json, OllamaChatResponse.class);
        } catch (JsonProcessingException e) {
            throw new SystemRuntimeException("解析Ollama 响应失败", e);
        }
    }

    @Override
    public OllamaEmbeddingResponse parseEmbeddingResponse(String json) {
        try {
            return JsonUtil.fromJson(json, OllamaEmbeddingResponse.class);
        } catch (JsonProcessingException e) {
            throw new SystemRuntimeException("解析 Ollama 响应失败", e);
        }
    }

    @Override
    public OllamaModelsResponse parseModelsResponse(String json) {
        try {
            return JsonUtil.fromJson(json, OllamaModelsResponse.class);
        } catch (JsonProcessingException e) {
            throw new SystemRuntimeException("无法反序列化：%s".formatted(e.getMessage()), e);
        }
    }
}
