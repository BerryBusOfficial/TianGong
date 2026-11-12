package com.taixu.tiangong.core.container.context.framework;

import com.taixu.tiangong.annotations.scanner.TianGongScanner;
import com.taixu.tiangong.core.agent.AgentLoopExecutor;
import com.taixu.tiangong.core.agent.runtime.AgentRuntime;
import com.taixu.tiangong.core.container.FrameworkContainer;
import com.taixu.tiangong.core.function.FunctionRegistry;
import com.taixu.tiangong.core.llm.LlmRegistry;
import com.taixu.tiangong.core.storage.Storage;
import com.taixu.tiangong.core.storage.StorageRecord;
import com.taixu.tiangong.core.thread.TaskExecutorRegistry;
import com.taixu.tiangong.core.thread.ThreadContextRegistry;

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
