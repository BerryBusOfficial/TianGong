package com.taixu.tiangong.core.container.context.application;

import com.taixu.tiangong.core.agent.AgentNodeRegistry;
import com.taixu.tiangong.core.container.Container;
import com.taixu.tiangong.core.function.FunctionRegistry;
import com.taixu.tiangong.core.llm.LlmRegistry;
import com.taixu.tiangong.core.sandbox.SandboxRegistry;

public sealed interface ApplicationContext permits
        TianGongApplicationContext {

    Container getContainer();

    LlmRegistry getLlmRegistry();

    FunctionRegistry getFunctionRegistry();

    AgentNodeRegistry getAgentNodeRegistry();

    /**
     * 沙箱注册表：命令节点从这里解析 {@code @Sandbox} 指定的执行环境。<br>
     * 默认含 {@code LocalSandbox}（本机直接执行、无隔离）。
     */
    SandboxRegistry getSandboxRegistry();

}
