package org.wutong.tiangong.core.container.context.framework;

import org.wutong.tiangong.annotations.scanner.TianGongScanner;
import org.wutong.tiangong.core.agent.AgentLoopExecutor;
import org.wutong.tiangong.core.agent.runtime.AgentRuntime;
import org.wutong.tiangong.core.container.FrameworkContainer;
import org.wutong.tiangong.core.function.FunctionRegistry;
import org.wutong.tiangong.core.llm.LlmRegistry;
import org.wutong.tiangong.core.storage.Storage;
import org.wutong.tiangong.core.storage.StorageRecord;
import org.wutong.tiangong.core.thread.TaskExecutorRegistry;
import org.wutong.tiangong.core.thread.ThreadContextRegistry;

/**
 * 功能配置上下文：@Enable* 装配类可访问的框架组件入口。<br>
 * 由框架注入，装配类在 {@code configure} 中按需取用。
 */
public interface FrameworkConfigurator {

    TianGongScanner scanner();

    FrameworkContainer container();

    LlmRegistry llmRegistry();

    FunctionRegistry functionRegistry();

    TaskExecutorRegistry taskExecutorRegistry();

    ThreadContextRegistry threadContextRegistry();

    /**
     * 泛型方法：按需要的记录类型获取存储（调用方零强转）
     */
    <ID, R extends StorageRecord<ID>> Storage<R, ID> storage();

    /**
     * 声明式注册额外 Bean（无 @Component 注解的普通类）
     */
    void registerBean(Class<?> type);

    /**
     * AgentLoop 执行引擎
     */
    AgentLoopExecutor agentLoopExecutor();

    /**
     * 注册 AgentRuntime。装配类创建 runtime 后调用此方法注册，
     * 框架在容器初始化完成后自动启动。
     */
    void registerAgentRuntime(AgentRuntime runtime);
}
