package org.wutong.tiangong.llm.api.request;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.wutong.tiangong.common.entity.SomeTypeReference;
import org.wutong.tiangong.common.exception.SystemRuntimeException;
import org.wutong.tiangong.common.type.ModelType;
import org.wutong.tiangong.common.util.JsonUtil;

import java.util.Map;

public interface Request<T extends ModelType> {

    default String toJson() {
        try {
            return JsonUtil.toJson(this);
        } catch (JsonProcessingException e) {
            throw new SystemRuntimeException("转 Json 字符串失败", e);
        }
    }

    default Map<String, Object> toMap() {
        return JsonUtil.convertToBean(this, SomeTypeReference.MAP_STRING_OBJECT);
    }

}
