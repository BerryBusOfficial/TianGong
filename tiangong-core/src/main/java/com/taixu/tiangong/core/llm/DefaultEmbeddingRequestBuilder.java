package com.taixu.tiangong.core.llm;

import com.taixu.tiangong.llm.api.request.embedding.EmbeddingRequest;
import com.taixu.tiangong.llm.options.embedding.EmbeddingOptions;

import java.util.List;

public final class DefaultEmbeddingRequestBuilder implements EmbeddingRequestBuilder {

    private final LlmRegistry registry;
    private final String clientName;

    public DefaultEmbeddingRequestBuilder(LlmRegistry registry, String clientName) {
        this.registry = registry;
        this.clientName = clientName;
    }

    @Override
    public EmbeddingRequest build(List<String> input) {
        return registry.buildEmbeddingRequest(clientName, input);
    }

    @Override
    public EmbeddingRequest build(List<String> input, EmbeddingOptions<?> options) {
        return registry.buildEmbeddingRequest(clientName, input, options);
    }
}
