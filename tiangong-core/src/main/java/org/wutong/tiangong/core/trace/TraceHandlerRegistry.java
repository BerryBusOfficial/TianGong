package org.wutong.tiangong.core.trace;

import org.wutong.tiangong.annotations.processor.registry.Registry;

import java.util.Collection;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 跟踪处理器注册表：容器组件实现 {@link TraceHandler} 时由 TraceHandlerProcessor 注册。<br>
 * 注册表引用固定、内容动态，拦截器事件时遍历，无时序问题。
 */
public class TraceHandlerRegistry implements Registry<TraceHandler> {

    private final Collection<TraceHandler> handlers = new CopyOnWriteArrayList<>();

    @Override
    public void register(TraceHandler element) {
        handlers.add(element);
    }

    @Override
    public void unregister(String name) {
        handlers.removeIf(handler -> handler.getClass().getName().equals(name));
    }

    @Override
    public Optional<TraceHandler> get(String name) {
        return handlers.stream()
                .filter(handler -> handler.getClass().getName().equals(name))
                .findFirst();
    }

    @Override
    public Collection<TraceHandler> values() {
        return handlers;
    }

    @Override
    public boolean contains(String name) {
        return handlers.stream().anyMatch(handler -> handler.getClass().getName().equals(name));
    }

    @Override
    public int size() {
        return handlers.size();
    }

    @Override
    public Iterator<TraceHandler> iterator() {
        return handlers.iterator();
    }
}
