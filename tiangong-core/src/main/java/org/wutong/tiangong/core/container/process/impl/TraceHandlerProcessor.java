package org.wutong.tiangong.core.container.process.impl;

import org.wutong.tiangong.annotations.annotation.definition.BeanDefinition;
import org.wutong.tiangong.core.container.process.AnnotationProcessor;
import org.wutong.tiangong.core.trace.TraceHandler;
import org.wutong.tiangong.core.trace.TraceHandlerRegistry;

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
