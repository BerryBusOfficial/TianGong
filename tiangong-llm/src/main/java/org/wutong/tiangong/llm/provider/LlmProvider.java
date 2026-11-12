package org.wutong.tiangong.llm.provider;

import org.wutong.tiangong.common.exception.SystemRuntimeException;
import org.wutong.tiangong.common.type.Chat;
import org.wutong.tiangong.common.type.Embedding;
import org.wutong.tiangong.common.type.ProviderType;
import org.wutong.tiangong.llm.api.request.Request;
import org.wutong.tiangong.llm.api.request.chat.ChatRequest;
import org.wutong.tiangong.llm.api.request.embedding.EmbeddingRequest;
import org.wutong.tiangong.llm.api.response.chat.ChatResponse;
import org.wutong.tiangong.llm.api.response.chat.chunk.ChatChunkResponse;
import org.wutong.tiangong.llm.api.response.embed.EmbeddingResponse;
import org.wutong.tiangong.llm.api.response.model.ModelsResponse;

public interface LlmProvider<T extends ProviderType> {

    Request<? extends Chat> toChatRequest(ChatRequest request);

    Request<? extends Embedding> toEmbeddingRequest(EmbeddingRequest request);

    ChatResponse<? extends T> parseChatResponse(String json);

    ChatChunkResponse<? extends T> parseChunk(String json);

    /**
     * 默认可不实现，有的模型没有embedding模型
     *
     * @param json 待解析的JSON字符串
     * @return 解析结果实例
     */
    default EmbeddingResponse<? extends T> parseEmbeddingResponse(String json) {
        throw new SystemRuntimeException("该模型不支持调用 embedding 方法", new IllegalCallerException());
    }

    /**
     * 默认可不实现，有的模型不支持getModels接口
     *
     * @param json 待解析的JSON字符串
     * @return 解析结果实例
     */
    default ModelsResponse<? extends T> parseModelsResponse(String json) {
        throw new SystemRuntimeException("该模型不支持调用 models 方法", new IllegalCallerException());
    }

}
