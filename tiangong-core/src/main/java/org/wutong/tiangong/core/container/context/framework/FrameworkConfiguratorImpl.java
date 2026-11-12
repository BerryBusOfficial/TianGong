package org.wutong.tiangong.core.container.context.framework;

import lombok.RequiredArgsConstructor;
import org.wutong.tiangong.annotations.scanner.TianGongScanner;
import org.wutong.tiangong.annotations.annotation.definition.BeanDefinition;
import org.wutong.tiangong.annotations.annotation.definition.Lifecycle;
import org.wutong.tiangong.annotations.processor.registry.BeanDefinitionRegistry;
import org.wutong.tiangong.core.agent.AgentLoopExecutor;
import org.wutong.tiangong.core.agent.runtime.AgentRuntime;
import org.wutong.tiangong.core.container.FrameworkContainer;
import org.wutong.tiangong.core.function.FunctionRegistry;
import org.wutong.tiangong.core.llm.LlmRegistry;
import org.wutong.tiangong.core.storage.Storage;
import org.wutong.tiangong.core.thread.TaskExecutorRegistry;
import org.wutong.tiangong.core.thread.ThreadContextRegistry;

/**
 * {@link FrameworkConfigurator} 默认实现，包装容器创建阶段的框架组件。
 */
@RequiredArgsConstructor
public class FrameworkConfiguratorImpl implements FrameworkConfigurator {

    private final BeanDefinitionRegistry registry;
    private final TianGongScanner scanner;
    private final FrameworkContainer container;
    private final LlmRegistry llmRegistry;
    private final FunctionRegistry functionRegistry;
    private final TaskExecutorRegistry taskExecutorRegistry;
    private final ThreadContextRegistry threadContextRegistry;
    private final Storage<?, ?> storage;
    private final AgentLoopExecutor agentLoopExecutor;

    private AgentRuntime agentRuntime;

    @Override
    public TianGongScanner scanner() {
        return scanner;
    }

    @Override
    public FrameworkContainer container() {
        return container;
    }

    @Override
    public LlmRegistry llmRegistry() {
        return llmRegistry;
    }

    @Override
    public FunctionRegistry functionRegistry() {
        return functionRegistry;
    }

    @Override
    public TaskExecutorRegistry taskExecutorRegistry() {
        return taskExecutorRegistry;
    }

    @Override
    public ThreadContextRegistry threadContextRegistry() {
        return threadContextRegistry;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <ID, R extends org.wutong.tiangong.core.storage.StorageRecord<ID>> Storage<R, ID> storage() {
        return (Storage<R, ID>) storage;
    }

    @Override
    public void registerBean(Class<?> type) {
        BeanDefinition definition = BeanDefinition.builder()
                .beanName(type.getSimpleName())
                .beanClass(type)
                .lifecycle(Lifecycle.SINGLETON)
                .lazyInit(false)
                .build();
        registry.register(definition);
    }

    @Override
    public AgentLoopExecutor agentLoopExecutor() {
        return agentLoopExecutor;
    }

    @Override
    public void registerAgentRuntime(AgentRuntime runtime) {
        this.agentRuntime = runtime;
    }

    /**
     * 获取已注册的 AgentRuntime（框架内部使用，create() 末尾启动）
     */
    public AgentRuntime getAgentRuntime() {
        return agentRuntime;
    }
}
