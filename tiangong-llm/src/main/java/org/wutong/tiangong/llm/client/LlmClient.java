package org.wutong.tiangong.llm.client;

import org.wutong.tiangong.common.exception.SystemRuntimeException;
import org.wutong.tiangong.common.type.Model;
import org.wutong.tiangong.common.type.ProviderType;
import org.wutong.tiangong.llm.api.Api;
import org.wutong.tiangong.llm.api.request.Request;
import org.wutong.tiangong.llm.api.request.chat.ChatRequest;
import org.wutong.tiangong.llm.api.request.embedding.EmbeddingRequest;
import org.wutong.tiangong.llm.api.response.chat.ChatResponse;
import org.wutong.tiangong.llm.api.response.chat.chunk.ChatChunkResponse;
import org.wutong.tiangong.llm.api.response.embed.EmbeddingResponse;
import org.wutong.tiangong.llm.api.response.model.ModelsResponse;
import org.wutong.tiangong.llm.event.result.ChatResult;
import org.wutong.tiangong.llm.event.stream.ChatStream;
import org.wutong.tiangong.llm.provider.LlmProvider;

public interface LlmClient<T extends ProviderType, P extends LlmProvider<T>> {

    P getProvider();

    Api getApi();

    ChatResult<? extends ChatResponse<? extends T>> chat(ChatRequest chatRequest);

    ChatStream<? extends ChatChunkResponse<? extends T>, ?> stream(ChatRequest request);

    default EmbeddingResponse<? extends T> embedding(EmbeddingRequest request) {
        throw new SystemRuntimeException("该模型不支持调用 embedding 方法", new IllegalCallerException());
    }

    default ModelsResponse<? extends T> models(Request<Model> request) {
        throw new SystemRuntimeException("该模型不支持调用 models 方法", new IllegalCallerException());
    }
}
