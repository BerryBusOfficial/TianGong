package com.taixu.tiangong.math;

import com.fasterxml.jackson.core.type.TypeReference;
import com.taixu.tiangong.common.util.JsonUtil;

import java.util.HashMap;
import java.util.Map;

public class Param {

    private final Map<String, Object> params = new HashMap<>();

    public Param(Map<String, Object> params) {
        this.params.putAll(params);
    }

    public Param(Object param) {
        params.put(param.toString(), param);
    }

    public Object getParam(String key) {
        return params.get(key);
    }

    public <T> T getParam(String key, Class<T> clazz) {
        return JsonUtil.convertToBean(params.get(key), clazz);
    }

    public <T> T getParam(String key, TypeReference<T> typeReference) {
        return JsonUtil.convertToBean(params.get(key), typeReference);
    }

    public Object getParamOrDefault(String key, Object defaultValue) {
        return params.getOrDefault(key, defaultValue);
    }

    public <T> T getParamOrDefault(String key, Class<T> clazz, T defaultValue) {
        Object value = getParamOrDefault(key, defaultValue);
        return JsonUtil.convertToBean(value, clazz);
    }

}
