package com.taixu.tiangong.core.container.process.impl;

import com.taixu.tiangong.annotations.annotation.definition.BeanDefinition;
import com.taixu.tiangong.annotations.annotation.framework.thread.ThreadPool;
import com.taixu.tiangong.common.exception.SystemRuntimeException;
import com.taixu.tiangong.core.container.process.AnnotationProcessor;
import com.taixu.tiangong.core.thread.TaskExecutor;
import com.taixu.tiangong.core.thread.TaskExecutorRegistry;

import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;

public class AgentThreadPoolProcessor implements AnnotationProcessor {

    private final TaskExecutorRegistry registry;

    public AgentThreadPoolProcessor(TaskExecutorRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void process(Object instance, BeanDefinition definition) throws Exception {
        // 用原始类扫 @AgentThreadPool 方法（增强子类的 getDeclaredMethods 不含父类方法）
        for (Method method : definition.getBeanClass().getDeclaredMethods()) {
            ThreadPool ann = method.getAnnotation(ThreadPool.class);
            if (ann == null) continue;

            method.setAccessible(true);
            Object result = method.invoke(instance);   // 在增强实例上调用（可访问字段）
            if (result instanceof ExecutorService es) {
                registry.register(ann.value(), es);
            } else if (result instanceof TaskExecutor te) {
                registry.register(ann.value(), te);
            } else {
                throw new SystemRuntimeException(
                        "@AgentThreadPool 方法必须返回 ExecutorService 或 TaskExecutor: "
                                + method.getName());
            }
        }
    }
}
