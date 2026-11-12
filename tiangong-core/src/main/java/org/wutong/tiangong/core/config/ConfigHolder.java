package org.wutong.tiangong.core.config;

import com.fasterxml.jackson.core.type.TypeReference;
import org.wutong.tiangong.common.exception.SystemRuntimeException;

import java.util.Map;

public final class ConfigHolder {

    private static org.wutong.tiangong.common.config.ConfigTree config;

    static void init(org.wutong.tiangong.common.config.ConfigTree source) {
        config = source;
    }

    static void init(Map<String, Object> source) {
        config = new org.wutong.tiangong.common.config.ConfigTree(source);
    }

    public static Object getValue(String key) {
        return config.get(key);
    }

    public static <T> T getValue(String key, Class<T> clazz) {
        return config.get(key, clazz);
    }

    public static <T> T getValue(String key, TypeReference<T> typeReference) {
        return config.get(key, typeReference);
    }

    /**
     * 取配置，缺失或整条路径不存在时返回 {@code defaultValue}。<br>
     * <b>配置尚未加载时也返回默认值</b>：旧实现直接 {@code config.get(...)}，
     * 而 config 是静态字段，未 init 时为 null —— 于是「还没加载配置就用 @Value 默认值」
     * 这条合规路径会 NPE，且 NPE 不在 catch 范围内（只 catch SystemRuntimeException）。
     */
    public static <T> T getValueOrDefault(String key, Class<T> clazz, T defaultValue) {
        if (config == null) {
            return defaultValue;
        }
        T value;
        try {
            value = config.get(key, clazz);
        } catch (SystemRuntimeException ignore) {
            return defaultValue;
        }
        // ⚠ 关键：ConfigTree.get 只在「路径中间节点缺失」时抛异常，最后一段缺失时返回 null。
        // 所以「键不存在」有两种表现形式，光 catch 异常只兜住了一半 —— 剩下那一半会把
        // null 交给调用方（典型症状：Integer 拆箱 NPE）。这里两种一律退回 defaultValue。
        return value == null ? defaultValue : value;
    }

    public static <T> T getValueOrDefault(String key, TypeReference<T> typeReference, T defaultValue) {
        if (config == null) {
            return defaultValue;
        }
        T value;
        try {
            value = config.get(key, typeReference);
        } catch (SystemRuntimeException ignore) {
            return defaultValue;
        }
        return value == null ? defaultValue : value;
    }

}
