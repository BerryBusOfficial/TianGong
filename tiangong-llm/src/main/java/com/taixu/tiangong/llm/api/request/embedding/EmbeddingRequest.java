package com.taixu.tiangong.llm.api.request.embedding;

import lombok.Data;
import com.taixu.tiangong.common.type.Embedding;
import com.taixu.tiangong.llm.api.request.Request;
import com.taixu.tiangong.llm.options.embedding.EmbeddingOptions;

import java.util.List;

@Data
public class EmbeddingRequest implements Request<Embedding> {

    private final String model;
    private final List<String> input;
    private final EmbeddingOptions<?> option;

}
