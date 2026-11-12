package org.wutong.tiangong.core.thread;

import org.wutong.tiangong.annotations.processor.registry.Registry;
import org.wutong.tiangong.core.container.context.framework.ThreadContext;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 线程上下文注册表：taskId → ThreadContext，管理所有活跃后台任务的上下文。
 */
public class ThreadContextRegistry implements Registry<ThreadContext> {

    private final Map<String, ThreadContext> threads = new ConcurrentHashMap<>();

    @Override
    public void register(ThreadContext element) {
        threads.put(element.getId(), element);
    }

    @Override
    public void unregister(String name) {
        threads.remove(name);
    }

    @Override
    public Optional<ThreadContext> get(String name) {
        return Optional.ofNullable(threads.get(name));
    }

    @Override
    public Collection<ThreadContext> values() {
        return threads.values();
    }

    @Override
    public boolean contains(String name) {
        return threads.containsKey(name);
    }

    @Override
    public int size() {
        return threads.size();
    }

    @Override
    public Iterator<ThreadContext> iterator() {
        return threads.values().iterator();
    }
}
