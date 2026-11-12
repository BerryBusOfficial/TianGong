package com.taixu.tiangong.test;

import com.taixu.tiangong.core.container.Container;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试用容器：手动注册 Bean，不启动完整框架。<br>
 * 用于 WorkflowScheduler / AgentRuntime 等需要 Container 的测试。
 */
public class TestContainer implements Container {

    private final Map<String, Object> beansByName = new ConcurrentHashMap<>();
    private final Map<Class<?>, Object> beansByType = new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getBean(String name) {
        return (T) beansByName.get(name);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getBean(String name, Class<T> type) {
        Object bean = beansByName.get(name);
        if (bean != null && type.isInstance(bean)) {
            return (T) bean;
        }
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getBean(Class<T> type) {
        Object bean = beansByType.get(type);
        if (bean != null) {
            return (T) bean;
        }
        // 尝试子类匹配
        for (Object b : beansByType.values()) {
            if (type.isInstance(b)) {
                return (T) b;
            }
        }
        return null;
    }

    @Override
    public void register(String name, Object instance) {
        beansByName.put(name, instance);
        beansByType.put(instance.getClass(), instance);
    }

    @Override
    public void refresh() {}

    /**
     * 注册类型映射（用于接口 → 实现的查找）。
     */
    public <T> void registerType(Class<T> type, T instance) {
        beansByType.put(type, instance);
        beansByType.put(instance.getClass(), instance);
    }
}
