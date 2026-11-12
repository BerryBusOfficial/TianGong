package com.taixu.tiangong.llm.api.response.model;

import com.taixu.tiangong.common.type.impl.Deepseek;

import java.util.List;

public record DeepseekModelsResponse(
        String object,
        List<Model> data
) implements ModelsResponse<Deepseek> {

    public record Model(
            String id,
            String object,
            String ownedBy
    ) {
    }

}
