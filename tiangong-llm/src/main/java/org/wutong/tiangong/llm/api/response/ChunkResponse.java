package org.wutong.tiangong.llm.api.response;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.wutong.tiangong.common.type.ModelType;
import org.wutong.tiangong.common.util.JsonUtil;

public interface ChunkResponse<T extends ModelType> {

    default String toJson() throws JsonProcessingException {
        return JsonUtil.toJson(this);
    }

}
