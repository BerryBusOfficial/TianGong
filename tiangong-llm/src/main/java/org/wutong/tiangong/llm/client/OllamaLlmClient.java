package org.wutong.tiangong.llm.client;

import org.wutong.tiangong.common.type.Model;
import org.wutong.tiangong.common.type.impl.Ollama;
import org.wutong.tiangong.llm.api.Api;
import org.wutong.tiangong.llm.api.OllamaApi;
import org.wutong.tiangong.llm.api.request.Request;
import org.wutong.tiangong.llm.api.request.chat.ChatRequest;
import org.wutong.tiangong.llm.api.request.chat.OllamaChatRequest;
import org.wutong.tiangong.llm.api.request.embedding.EmbeddingRequest;
import org.wutong.tiangong.llm.api.request.embedding.OllamaEmbeddingRequest;
import org.wutong.tiangong.llm.api.response.embed.OllamaEmbeddingResponse;
import org.wutong.tiangong.llm.api.response.model.OllamaModelsResponse;
import org.wutong.tiangong.llm.event.result.OllamaChatResult;
import org.wutong.tiangong.llm.event.stream.OllamaChatStream;
import org.wutong.tiangong.llm.provider.OllamaProvider;

import java.io.InputStream;
import java.util.Map;

@org.wutong.tiangong.annotations.annotation.framework.component.LlmClient(
        provider = Ollama.class
)
public class OllamaLlmClient implements org.wutong.tiangong.llm.client.LlmClient<Ollama, OllamaProvider> {

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
