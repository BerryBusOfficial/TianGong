package com.taixu.tiangong.llm.api.response.model;

import com.taixu.tiangong.common.type.impl.Openai;

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
