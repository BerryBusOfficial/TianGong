package com.taixu.tiangong.test;

import com.taixu.tiangong.core.agent.NodeExceptionAdviceRegistry;
import com.taixu.tiangong.annotations.annotation.definition.BeanDefinition;
import com.taixu.tiangong.annotations.annotation.definition.Lifecycle;
import com.taixu.tiangong.core.agent.AgentLoopExecutor;
import com.taixu.tiangong.core.agent.AgentNodeRegistry;
import com.taixu.tiangong.core.agent.processor.AgentNodeAnnotationProcessor;

/**
 * 测试装配工具：手动构造 registry + executor，不启动完整框架。<br>
 * 适用于 AgentLoop 执行器、节点跳转、运行时组件的单元/集成测试。
 */
public final class TestHarness {

    private TestHarness() {}

    /**
     * 装配 registry：扫描给定 Bean 的 @AgentNode 方法并注册。
     */
    public static AgentNodeRegistry registryFor(Object... beans) {
        AgentNodeRegistry registry = new AgentNodeRegistry();
        AgentNodeAnnotationProcessor processor = new AgentNodeAnnotationProcessor(registry);
        for (Object bean : beans) {
            processor.process(bean, BeanDefinition.builder()
                    .beanName(bean.getClass().getSimpleName())
                    .beanClass(bean.getClass())
                    .lifecycle(Lifecycle.SINGLETON)
                    .lazyInit(false)
                    .build());
        }
        return registry;
    }

    /**
     * 装配 executor：给定 Bean，返回带 registry 的 executor。
     */
    public static AgentLoopExecutor executorFor(Object... beans) {
        return new AgentLoopExecutor(registryFor(beans));
    }

    /**
     * 装配 executor + advice：带异常通知注册表。
     */
    public static AgentLoopExecutor executorWithAdvice(
            NodeExceptionAdviceRegistry advice,
            Object... beans) {
        return new AgentLoopExecutor(registryFor(beans), advice);
    }
}
