package org.wutong.tiangong.llm.api.response.model;

import org.wutong.tiangong.common.type.impl.Ollama;

import java.util.List;

public record OllamaModelsResponse(
        List<Model> models
) implements ModelsResponse<Ollama> {

    public record Model(
            String name,
            String model,
            String remoteModel,
            String remoteHost,
            String modifiedAt,
            Integer size,
            String digest,
            Details details
    ) {

        public record Details(
                String format,
                String family,
                List<String> families,
                String parameterSize,
                String quantizationLevel
        ) {
        }

    }

}
