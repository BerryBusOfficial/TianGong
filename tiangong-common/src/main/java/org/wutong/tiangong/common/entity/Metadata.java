package org.wutong.tiangong.common.entity;

import org.wutong.tiangong.common.exception.SystemRuntimeException;
import org.wutong.tiangong.common.util.JsonUtil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Metadata {

    private final Map<String, Object> metadata = new ConcurrentHashMap<>();

    public Metadata(Metadata other) {
        this.metadata.putAll(other.metadata);
    }

    public Metadata(Map<String, Object> metadata) {
        this.metadata.putAll(metadata);
    }

    public Metadata() {
    }

    public <T> T put(String key, T value) {
        metadata.put(key, value);
        return value;
    }

    public <T> T get(String key, Class<T> clazz) {
        if (metadata.containsKey(key)) {
            return JsonUtil.convertToBean(key, clazz);
        }
        throw new SystemRuntimeException(key + " not found");
    }

    public void remove(String key) {
        metadata.remove(key);
    }

}
