package org.wutong.tiangong.llm.api.response.model;

import org.wutong.tiangong.common.type.impl.Openai;

import java.util.List;

public record OpenaiModelsResponse(
        String object,
        List<Model> data
) implements ModelsResponse<Openai> {

    public record Model(
            String id,
            String object,
            Long created,
            String ownedBy
    ) {
    }

}
