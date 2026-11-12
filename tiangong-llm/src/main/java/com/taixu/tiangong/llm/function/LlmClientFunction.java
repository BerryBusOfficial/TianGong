package com.taixu.tiangong.llm.function;


import com.taixu.tiangong.common.exception.SystemRuntimeException;
import com.taixu.tiangong.common.type.Model;
import com.taixu.tiangong.common.type.ModelType;
import com.taixu.tiangong.common.type.ProviderType;
import com.taixu.tiangong.llm.api.request.Request;
import com.taixu.tiangong.llm.api.response.chat.ChatResponse;
import com.taixu.tiangong.llm.api.response.embed.EmbeddingResponse;
import com.taixu.tiangong.llm.api.response.model.ModelsResponse;
import com.taixu.tiangong.llm.client.LlmClient;
import com.taixu.tiangong.llm.event.result.ChatResult;
import com.taixu.tiangong.llm.provider.LlmProvider;

import java.util.List;

public interface LlmClientFunction<
        T extends ModelType & ProviderType,
        C extends LlmClient<T, ? extends LlmProvider<T>>
        > {

    C getClient();

    LlmProvider<T> getProvider();

    ChatResult<? extends ChatResponse<? extends T>> chat(String content);

    ChatResult<? extends ChatResponse<? extends T>> chat(List<String> session);

    default EmbeddingResponse<? extends T> embedding(String input) {
        throw new SystemRuntimeException("该模型不支持调用 embedding 方法", new IllegalCallerException());
    }

    default EmbeddingResponse<? extends T> embedding(List<String> batchInput) {
        throw new SystemRuntimeException("该模型不支持调用 embedding 方法", new IllegalCallerException());
    }

    default ModelsResponse<? extends T> models(Request<Model> request) {
        throw new SystemRuntimeException("该模型不支持调用 models 方法", new IllegalCallerException());
    }

}
