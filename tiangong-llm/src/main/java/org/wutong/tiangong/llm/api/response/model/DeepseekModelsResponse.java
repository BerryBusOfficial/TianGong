package org.wutong.tiangong.llm.api.response.model;

import org.wutong.tiangong.common.type.impl.Deepseek;

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
