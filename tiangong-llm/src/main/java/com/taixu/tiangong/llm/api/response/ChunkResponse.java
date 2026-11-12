package com.taixu.tiangong.llm.api.response;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.taixu.tiangong.common.type.ModelType;
import com.taixu.tiangong.common.util.JsonUtil;

public interface ChunkResponse<T extends ModelType> {

    default String toJson() throws JsonProcessingException {
        return JsonUtil.toJson(this);
    }

}
