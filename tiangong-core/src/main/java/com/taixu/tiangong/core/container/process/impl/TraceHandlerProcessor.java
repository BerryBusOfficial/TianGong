package com.taixu.tiangong.core.container.process.impl;

import com.taixu.tiangong.annotations.annotation.definition.BeanDefinition;
import com.taixu.tiangong.core.container.process.AnnotationProcessor;
import com.taixu.tiangong.core.trace.TraceHandler;
import com.taixu.tiangong.core.trace.TraceHandlerRegistry;

/**
 * 注册 TraceHandler 组件到 {@link TraceHandlerRegistry}（实例化后识别，无时序问题）。
 */
public class TraceHandlerProcessor implements AnnotationProcessor {

    private final TraceHandlerRegistry registry;

    public TraceHandlerProcessor(TraceHandlerRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void process(Object instance, BeanDefinition definition) {
        if (instance instanceof TraceHandler handler) {
            registry.register(handler);
        }
    }
}
