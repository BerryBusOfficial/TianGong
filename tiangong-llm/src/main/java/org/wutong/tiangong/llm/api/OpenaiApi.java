package org.wutong.tiangong.llm.api;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.wutong.tiangong.common.exception.UnsupportedInvocationException;
import org.wutong.tiangong.common.transport.HttpTransport;
import org.wutong.tiangong.llm.builder.RequestBuilder;

import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

@Data
@Slf4j
public class OpenaiApi implements Api {

    private final static HttpTransport TRANSPORT = new HttpTransport();
    private final String baseUrl;
    private final String apiKey;

    public OpenaiApi(String baseUrl) {
        this.baseUrl = baseUrl;
        this.apiKey = System.getenv("OPENAI_API_KEY");
    }

    public OpenaiApi(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    @Override
    public HttpTransport getHttpTransport() {
        return TRANSPORT;
    }

    @Override
    public String getGenerateEndpoint() {
        return getChatCompletionEndpoint();
    }

    @Override
    public String getChatCompletionEndpoint() {
        return "/v1/chat/completions";
    }

    @Override
    public String getEmbeddingsEndpoint() {
        return "/v1/embeddings";
    }

    @Override
    public String getModelsEndpoint() {
        return "/v1/models";
    }

    @Override
    public String chatCompletion(Map<String, Object> body) {
        if (!isSupportedInvocation(Endpoint.ChatCompletion)) {
            throw new UnsupportedInvocationException();
        }
        HttpRequest httpRequest = new RequestBuilder()
                .uri(baseUrl, getChatCompletionEndpoint())
                .method(RequestBuilder.Method.POST)
                .body(body)
                .auth(apiKey)
                .build();
        HttpResponse<String> response = getHttpTransport().send(httpRequest);
        return whenNormalReturn(response);
    }

    @Override
    public InputStream chatCompletionStream(Map<String, Object> body) {
        if (!isSupportedInvocation(Endpoint.ChatCompletion)) {
            throw new UnsupportedInvocationException();
        }
//        log.debug("原始Body: {}", body);
        HttpRequest httpRequest = new RequestBuilder()
                .uri(baseUrl, getChatCompletionEndpoint())
                .method(RequestBuilder.Method.POST)
                .body(body)
                .auth(apiKey)
                .build();
        HttpResponse<InputStream> response = getHttpTransport().stream(httpRequest);
        return whenNormalReturn(response);
    }

    @Override
    public String embeddings(Map<String, Object> body) {
        if (!isSupportedInvocation(Endpoint.Embedding)) {
            throw new UnsupportedInvocationException();
        }
        HttpRequest httpRequest = new RequestBuilder()
                .uri(baseUrl, getEmbeddingsEndpoint())
                .method(RequestBuilder.Method.POST)
                .body(body)
                .auth(apiKey)
                .build();
        HttpResponse<String> response = getHttpTransport().send(httpRequest);
        return whenNormalReturn(response);
    }

    @Override
    public String models(Map<String, Object> body) {
        if (!isSupportedInvocation(Endpoint.Models)) {
            throw new UnsupportedInvocationException();
        }
        HttpRequest httpRequest = new RequestBuilder()
                .uri(baseUrl, getModelsEndpoint())
                .method(RequestBuilder.Method.GET)
                .body(body)
                .auth(apiKey)
                .build();
        HttpResponse<String> response = getHttpTransport().send(httpRequest);
        return whenNormalReturn(response);
    }
}
