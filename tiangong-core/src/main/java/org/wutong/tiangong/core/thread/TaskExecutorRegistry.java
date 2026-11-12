package org.wutong.tiangong.core.thread;

import org.wutong.tiangong.annotations.processor.registry.Registry;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * TaskExecutor 注册表：name → TaskExecutor。<br>
 * 默认注册 "default"（虚拟线程）；用户可注册自定义线程池后按名引用。
 */
public class TaskExecutorRegistry implements Registry<TaskExecutor> {

    private final ThreadContextRegistry threadContextRegistry;
    private final Map<String, TaskExecutor> executors = new ConcurrentHashMap<>();

    /**
     * 构造时注入全局线程注册表（所有池共享）
     */
    public TaskExecutorRegistry(ThreadContextRegistry threadContextRegistry) {
        this.threadContextRegistry = threadContextRegistry;
    }

    /**
     * 便捷注册：传入 ExecutorService，内部自动包装进全局线程注册表
     */
    public void register(String name, ExecutorService executor) {
        register(name, new TaskExecutor(name, executor, threadContextRegistry));
    }

    /**
     * 完整注册：完全自定义 TaskExecutor（含自定义 registry）
     */
    public void register(String name, TaskExecutor executor) {
        executors.put(name, executor);
    }

    @Override
    public void register(TaskExecutor element) {
        executors.put(element.getName(), element);
    }

    @Override
    public void unregister(String name) {
        executors.remove(name);
    }

    @Override
    public Optional<TaskExecutor> get(String name) {
        return Optional.ofNullable(executors.get(name));
    }

    @Override
    public Collection<TaskExecutor> values() {
        return executors.values();
    }

    @Override
    public boolean contains(String name) {
        return executors.containsKey(name);
    }

    @Override
    public int size() {
        return executors.size();
    }

    @Override
    public Iterator<TaskExecutor> iterator() {
        return executors.values().iterator();
    }
}