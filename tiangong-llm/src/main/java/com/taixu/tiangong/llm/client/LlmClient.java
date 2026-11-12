package com.taixu.tiangong.llm.client;

import com.taixu.tiangong.common.exception.SystemRuntimeException;
import com.taixu.tiangong.common.type.Model;
import com.taixu.tiangong.common.type.ProviderType;
import com.taixu.tiangong.llm.api.Api;
import com.taixu.tiangong.llm.api.request.Request;
import com.taixu.tiangong.llm.api.request.chat.ChatRequest;
import com.taixu.tiangong.llm.api.request.embedding.EmbeddingRequest;
import com.taixu.tiangong.llm.api.response.chat.ChatResponse;
import com.taixu.tiangong.llm.api.response.chat.chunk.ChatChunkResponse;
import com.taixu.tiangong.llm.api.response.embed.EmbeddingResponse;
import com.taixu.tiangong.llm.api.response.model.ModelsResponse;
import com.taixu.tiangong.llm.event.result.ChatResult;
import com.taixu.tiangong.llm.event.stream.ChatStream;
import com.taixu.tiangong.llm.provider.LlmProvider;

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
