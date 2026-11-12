package org.wutong.tiangong.llm.api.response.embed;

import org.wutong.tiangong.common.type.Embedding;
import org.wutong.tiangong.llm.api.response.Response;

import java.util.List;

public interface EmbeddingResponse<E extends Embedding> extends Response<E> {

    List<List<Double>> getEmbedding();

}
