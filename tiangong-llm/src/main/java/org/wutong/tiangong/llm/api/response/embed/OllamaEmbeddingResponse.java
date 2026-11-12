package org.wutong.tiangong.llm.api.response.embed;

import org.wutong.tiangong.common.type.impl.Ollama;

import java.util.List;
import java.util.stream.Collectors;

public record OllamaEmbeddingResponse(
        String model,
        List<List<Number>> embeddings,
        Long totalDuration,
        Long longDuration,
        Long loadDuration,
        Long promptEvalCount
) implements EmbeddingResponse<Ollama> {

    @Override
    public List<List<Double>> getEmbedding() {
        return embeddings().stream()
                .map(innerList -> innerList == null ? null : innerList.stream()
                                                             .map(number -> number == null ? null : number.doubleValue())
                                                             .collect(Collectors.toList()))
                .collect(Collectors.toList());
    }
}
