package com.taixu.tiangong.common.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.taixu.tiangong.common.exception.SystemRuntimeException;
import com.taixu.tiangong.common.util.JsonUtil;

import java.util.Map;
import java.util.TreeMap;

public class ConfigTree {

    private final Map<String, Object> config = new TreeMap<>();

    public ConfigTree(Map<String, Object> config) {
        this.config.putAll(config);
    }

    public Object get(String key) {
        if (key == null || key.isEmpty()) {
            throw new SystemRuntimeException("错误的键路径", new IllegalArgumentException(key));
        }

        String[] segments = key.split("\\.");
        Object current = config;

        for (String segment : segments) {
            if (current == null) {
                throw new SystemRuntimeException("错误的键路径: %s".formatted(key), new IllegalArgumentException(key));
            }
            if (current instanceof Map<?, ?> map) {
                current = map.get(segment);
            } else {
                throw new SystemRuntimeException("错误的键路径: %s".formatted(key), new IllegalArgumentException(key));
            }
        }

        return current;
    }

    public <T> T get(String key, Class<T> clazz) {
        Object value = get(key);
        try {
            return JsonUtil.convertToBean(value, clazz);
        } catch (IllegalArgumentException e) {
            throw new SystemRuntimeException(e.getMessage(), e);
        }
    }

    public <T> T get(String key, TypeReference<T> typeReference) {
        Object value = get(key);
        try {
            return JsonUtil.convertToBean(value, typeReference);
        } catch (IllegalArgumentException e) {
            throw new SystemRuntimeException(e.getMessage(), e);
        }
    }

    /**
     * 按路径取值，取不到时返回 {@code defaultValue}。<br>
     * <b>修过一个隐蔽 bug</b>：旧实现是 {@code config.get(key)}，只查顶层 Map，
     * 于是任何带点的键（{@code llm.request-timeout}）永远取不到、直接返回默认值；
     * 更糟的是它和 {@link #get(String)} 语义不一致 —— 同一个键两种方法结论相反。
     * 现在路径不存在（中间节点缺失也算）一律走默认值，与「配置键可缺省」的直觉一致。
     */
    public Object getOrDefault(String key, Object defaultValue) {
        Object value = lookup(key);
        return value == null ? defaultValue : value;
    }

    public <T> T getOrDefault(String key, Class<T> clazz, T defaultValue) {
        Object value = lookup(key);
        if (value == null) {
            value = defaultValue;
        }
        try {
            return JsonUtil.convertToBean(value, clazz);
        } catch (IllegalArgumentException e) {
            throw new SystemRuntimeException(e.getMessage(), e);
        }
    }

    public <T> T getOrDefault(String key, TypeReference<T> typeReference, T defaultValue) {
        Object value = lookup(key);
        if (value == null) {
            value = defaultValue;
        }
        try {
            return JsonUtil.convertToBean(value, typeReference);
        } catch (IllegalArgumentException e) {
            throw new SystemRuntimeException(e.getMessage(), e);
        }
    }

    /**
     * 沿路径查值，路径不存在或中间节点缺失时返回 {@code null}（不抛）。
     * 仅供 getOrDefault 系列使用 —— {@code get} 系列保持「缺键即报错」的严格语义。
     */
    private Object lookup(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        Object current = config;
        for (String segment : key.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

}
