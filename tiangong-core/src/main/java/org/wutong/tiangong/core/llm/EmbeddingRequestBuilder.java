package org.wutong.tiangong.core.llm;

import org.wutong.tiangong.llm.api.request.embedding.EmbeddingRequest;
import org.wutong.tiangong.llm.options.embedding.EmbeddingOptions;

import java.util.List;

/**
 * 绑定指定客户端的 EmbeddingRequest 构建器。
 * model / options 自动从配置填充，input 由调用方传入。
 */
public sealed interface EmbeddingRequestBuilder permits DefaultEmbeddingRequestBuilder {

    /**
     * options 用配置绑定值
     */
    EmbeddingRequest build(List<String> input);

    /**
     * 显式覆盖 options
     */
    EmbeddingRequest build(List<String> input, EmbeddingOptions<?> options);
}
