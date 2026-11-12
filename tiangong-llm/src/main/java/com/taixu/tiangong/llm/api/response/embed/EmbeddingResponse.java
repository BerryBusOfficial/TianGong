package com.taixu.tiangong.llm.api.response.embed;

import com.taixu.tiangong.common.type.Embedding;
import com.taixu.tiangong.llm.api.response.Response;

import java.util.List;

public interface EmbeddingResponse<E extends Embedding> extends Response<E> {

    List<List<Double>> getEmbedding();

}
