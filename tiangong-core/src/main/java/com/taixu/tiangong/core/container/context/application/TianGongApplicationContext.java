package com.taixu.tiangong.core.container.context.application;

import com.taixu.tiangong.annotations.scanner.TianGongScanner;
import com.taixu.tiangong.core.agent.AgentLoopExecutor;
import com.taixu.tiangong.core.agent.AgentNodeRegistry;
import com.taixu.tiangong.core.agent.runtime.AgentRuntime;
import com.taixu.tiangong.core.api.entrance.ServiceEntranceRegistry;
import com.taixu.tiangong.core.container.Container;
import com.taixu.tiangong.core.container.FrameworkContainer;
import com.taixu.tiangong.core.container.context.ContextHolder;
import com.taixu.tiangong.core.container.context.framework.ApplicationFrameworkContext;
import com.taixu.tiangong.core.function.FunctionRegistry;
import com.taixu.tiangong.core.llm.LlmRegistry;
import com.taixu.tiangong.core.sandbox.SandboxRegistry;

import java.util.function.Consumer;

public final class TianGongApplicationContext implements ApplicationContext {

    private final ApplicationFrameworkContext ctx;

    private TianGongApplicationContext(ApplicationFrameworkContext ctx) {
        this.ctx = ctx;
    }

    public static TianGongApplicationContext create() {
        ApplicationFrameworkContext fwCtx = ApplicationFrameworkContext.create();
        ContextHolder.setApplication(fwCtx);
        return new TianGongApplicationContext(fwCtx);
    }

    public static TianGongApplicationContext create(Consumer<TianGongScanner> scannerConfig,
                                                 Consumer<FrameworkContainer> containerConfig) {
        ApplicationFrameworkContext fwCtx = ApplicationFrameworkContext.create(scannerConfig, containerConfig);
        ContextHolder.setApplication(fwCtx);
        return new TianGongApplicationContext(fwCtx);
    }

    @Override
    public Container getContainer() {
        return ctx.getContainer();
    }

    @Override
    public LlmRegistry getLlmRegistry() {
        return ctx.getLlmRegistry();
    }

    @Override
    public FunctionRegistry getFunctionRegistry() {
        return ctx.getFunctionRegistry();
    }

    @Override
    public AgentNodeRegistry getAgentNodeRegistry() {
        return ctx.getAgentNodeRegistry();
    }

    @Override
    public SandboxRegistry getSandboxRegistry() {
        return ctx.getSandboxRegistry();
    }

    public AgentLoopExecutor getAgentLoopExecutor() {
        return ctx.getAgentLoopExecutor();
    }

    /**
     * 服务入口注册表：宿主集成（桥接层）经它提交进程内服务调用、查询已注册地址。
     */
    public ServiceEntranceRegistry getServiceEntranceRegistry() {
        return ctx.getServiceEntranceRegistry();
    }

    /**
     * AgentRuntime；未开启 {@code @EnableAgentLoop} 时为 null。<br>
     * 宿主模式（{@code BootPolicy.runtime=ASYNC/NONE}）由宿主在关闭时调其 {@code shutdown()}。
     */
    public AgentRuntime getAgentRuntime() {
        return ctx.getAgentRuntime();
    }
}
