package com.taixu.tiangong.llm.client;

import com.taixu.tiangong.common.type.Model;
import com.taixu.tiangong.common.type.impl.Ollama;
import com.taixu.tiangong.llm.api.Api;
import com.taixu.tiangong.llm.api.OllamaApi;
import com.taixu.tiangong.llm.api.request.Request;
import com.taixu.tiangong.llm.api.request.chat.ChatRequest;
import com.taixu.tiangong.llm.api.request.chat.OllamaChatRequest;
import com.taixu.tiangong.llm.api.request.embedding.EmbeddingRequest;
import com.taixu.tiangong.llm.api.request.embedding.OllamaEmbeddingRequest;
import com.taixu.tiangong.llm.api.response.embed.OllamaEmbeddingResponse;
import com.taixu.tiangong.llm.api.response.model.OllamaModelsResponse;
import com.taixu.tiangong.llm.event.result.OllamaChatResult;
import com.taixu.tiangong.llm.event.stream.OllamaChatStream;
import com.taixu.tiangong.llm.provider.OllamaProvider;

import java.io.InputStream;
import java.util.Map;

@com.taixu.tiangong.annotations.annotation.framework.component.LlmClient(
        provider = Ollama.class
)
public class OllamaLlmClient implements LlmClient<Ollama, OllamaProvider> {

    private final OllamaProvider provider = new OllamaProvider();
    private final OllamaApi api;

    public OllamaLlmClient() {
        api = new OllamaApi();
    }

    public OllamaLlmClient(String baseUrl) {
        api = new OllamaApi(baseUrl);
    }

    public OllamaLlmClient(String baseUrl, String apiKey) {
        api = new OllamaApi(baseUrl, apiKey);
    }

    @Override
    public OllamaProvider getProvider() {
        return provider;
    }

    @Override
    public Api getApi() {
        return api;
    }

    @Override
    public OllamaChatResult chat(ChatRequest chatRequest) {
        try {
            OllamaChatRequest body = getProvider().toChatRequest(chatRequest);
            Map<String, Object> map = body.toMap();
            if (body.getStream() == null || body.getStream().equals(Boolean.TRUE)) {
                map.put("stream", false);
            }
            String responseBody = getApi().chatCompletion(map);
            return new OllamaChatResult(getProvider().parseChatResponse(responseBody));
        } catch (Exception e) {
            return new OllamaChatResult(null, e);   // 错误进 ChatResult：onError 回调 / join 抛出
        }
    }

    @Override
    public OllamaChatStream stream(ChatRequest request) {
        OllamaChatRequest body = getProvider().toChatRequest(request);
        Map<String, Object> map = body.toMap();
        if (body.getStream() == null || body.getStream().equals(Boolean.FALSE)) {
            map.put("stream", true);
        }
        InputStream stream = getApi().chatCompletionStream(map);
        return new OllamaChatStream(stream);
    }

    @Override
    public OllamaEmbeddingResponse embedding(EmbeddingRequest request) {
        OllamaEmbeddingRequest body = getProvider().toEmbeddingRequest(request);
        String responseBody = getApi().embeddings(body.toMap());
        return getProvider().parseEmbeddingResponse(responseBody);
    }

    @Override
    public OllamaModelsResponse models(Request<Model> request) {
        String responseBody = getApi().models(Map.of());
        return getProvider().parseModelsResponse(responseBody);
    }
}
