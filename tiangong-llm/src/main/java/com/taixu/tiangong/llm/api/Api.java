package com.taixu.tiangong.llm.api;

import com.taixu.tiangong.common.exception.ApiRequestException;
import com.taixu.tiangong.common.transport.HttpTransport;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public interface Api {

    /**
     * 不支持调用
     */
    String UNSUPPORTED_INVOCATION = "Unsupported invocation";

    HttpTransport getHttpTransport();

    String getGenerateEndpoint();

    String getChatCompletionEndpoint();

    String getEmbeddingsEndpoint();

    String getModelsEndpoint();

    String chatCompletion(Map<String, Object> body);

    String embeddings(Map<String, Object> body);

    String models(Map<String, Object> body);

    default String balance() {
        throw new UnsupportedOperationException();
    }

    InputStream chatCompletionStream(Map<String, Object> body);

    default <T> T whenNormalReturn(HttpResponse<T> response, Class<? extends RuntimeException> exceptionWithUnnormal) {
        if (response.statusCode() == 200) {
            return response.body();
        }
        throw ApiExceptionFactory.from(response.statusCode(), describeBody(response.body()));
    }

    default <T> T whenNormalReturn(HttpResponse<T> response) {
        return whenNormalReturn(response, ApiRequestException.class);
    }

    default String describeBody(Object body) {
        if (body instanceof InputStream in) {
            try {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "（读取错误响应失败）" + e.getMessage();
            }
        }
        return String.valueOf(body);
    }

    default boolean isSupportedInvocation(Endpoint endpoint) {
        return switch (endpoint) {
            case ChatCompletion -> !UNSUPPORTED_INVOCATION.equals(getChatCompletionEndpoint());
            case Embedding -> !UNSUPPORTED_INVOCATION.equals(getEmbeddingsEndpoint());
            case Models -> !UNSUPPORTED_INVOCATION.equals(getModelsEndpoint());
        };
    }

    enum Endpoint {
        ChatCompletion,
        Embedding,
        Models,
    }
}
