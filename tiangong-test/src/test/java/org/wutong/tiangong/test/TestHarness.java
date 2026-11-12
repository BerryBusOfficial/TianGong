package org.wutong.tiangong.test;

import org.wutong.tiangong.annotations.annotation.definition.BeanDefinition;
import org.wutong.tiangong.annotations.annotation.definition.Lifecycle;
import org.wutong.tiangong.core.agent.AgentLoopExecutor;
import org.wutong.tiangong.core.agent.AgentNodeRegistry;
import org.wutong.tiangong.core.agent.processor.AgentNodeAnnotationProcessor;

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
            org.wutong.tiangong.core.agent.NodeExceptionAdviceRegistry advice,
            Object... beans) {
        return new AgentLoopExecutor(registryFor(beans), advice);
    }
}
