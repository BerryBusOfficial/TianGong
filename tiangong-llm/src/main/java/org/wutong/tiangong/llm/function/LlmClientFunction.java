package org.wutong.tiangong.llm.function;


import org.wutong.tiangong.common.exception.SystemRuntimeException;
import org.wutong.tiangong.common.type.Model;
import org.wutong.tiangong.common.type.ModelType;
import org.wutong.tiangong.common.type.ProviderType;
import org.wutong.tiangong.llm.api.request.Request;
import org.wutong.tiangong.llm.api.response.chat.ChatResponse;
import org.wutong.tiangong.llm.api.response.embed.EmbeddingResponse;
import org.wutong.tiangong.llm.api.response.model.ModelsResponse;
import org.wutong.tiangong.llm.client.LlmClient;
import org.wutong.tiangong.llm.event.result.ChatResult;
import org.wutong.tiangong.llm.provider.LlmProvider;

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
