package org.wutong.tiangong.core.agent.runtime;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 进程内单例 {@link GlobalVariableStore}，基于 {@link ConcurrentHashMap}。
 */
public class SingletonGlobalVariableStore implements GlobalVariableStore {

    private final ConcurrentMap<String, Object> store = new ConcurrentHashMap<>();

    @Override
    public <T> void put(String key, T value) {
        store.put(key, value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String key, Class<T> type) {
        Object val = store.get(key);
        if (val == null) return Optional.empty();
        if (!type.isInstance(val)) {
            throw new IllegalStateException(
                    "全局变量 [" + key + "] 类型不匹配: 期望 " + type.getName()
                            + "，实际 " + val.getClass().getName());
        }
        return Optional.of((T) val);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> consume(String key, Class<T> type) {
        Object val = store.remove(key);
        if (val == null) return Optional.empty();
        if (!type.isInstance(val)) {
            throw new IllegalStateException(
                    "全局变量 [" + key + "] 类型不匹配: 期望 " + type.getName()
                            + "，实际 " + val.getClass().getName());
        }
        return Optional.of((T) val);
    }

    @Override
    public void remove(String key) {
        store.remove(key);
    }

    @Override
    public boolean contains(String key) {
        return store.containsKey(key);
    }
}
