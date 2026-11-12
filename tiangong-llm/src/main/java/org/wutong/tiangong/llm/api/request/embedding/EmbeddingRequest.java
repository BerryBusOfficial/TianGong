package org.wutong.tiangong.llm.api.request.embedding;

import lombok.Data;
import org.wutong.tiangong.common.type.Embedding;
import org.wutong.tiangong.llm.api.request.Request;
import org.wutong.tiangong.llm.options.embedding.EmbeddingOptions;

import java.util.List;

@Data
public class EmbeddingRequest implements Request<Embedding> {

    private final String model;
    private final List<String> input;
    private final EmbeddingOptions<?> option;

}
