package org.wutong.tiangong.core.agent;

import org.wutong.tiangong.annotations.annotation.definition.BeanDefinition;
import org.wutong.tiangong.annotations.annotation.framework.agent.AgentNodeExceptionAdvice;
import org.wutong.tiangong.annotations.annotation.framework.agent.NodeExceptionHandler;
import org.wutong.tiangong.core.container.process.AnnotationProcessor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @AgentNodeExceptionAdvice 注册处理器：Bean 实例化后扫描 advice 类，<br>
 * 收集其 @NodeExceptionHandler 方法注册到 {@link NodeExceptionAdviceRegistry}。
 */
public class NodeExceptionAdviceProcessor implements AnnotationProcessor {

    private final NodeExceptionAdviceRegistry registry;

    public NodeExceptionAdviceProcessor(NodeExceptionAdviceRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void process(Object instance, BeanDefinition definition) {
        Class<?> clazz = definition.getBeanClass();
        AgentNodeExceptionAdvice advice = clazz.getAnnotation(AgentNodeExceptionAdvice.class);
        if (advice == null) {
            return;
        }
        List<NodeExceptionHandlerDefinition> handlers = new ArrayList<>();
        for (Method method : clazz.getDeclaredMethods()) {
            NodeExceptionHandler handler = method.getAnnotation(NodeExceptionHandler.class);
            if (handler == null) {
                continue;
            }
            handlers.add(new NodeExceptionHandlerDefinition(
                    Arrays.asList(handler.exception()), instance, method));
        }
        registry.register(new NodeExceptionAdviceDefinition(
                Arrays.asList(advice.onClass()), handlers));
    }
}
