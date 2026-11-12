package com.taixu.tiangong.test.node;

import com.taixu.tiangong.annotations.annotation.framework.agent.loop.AgentNode;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeNext;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import com.taixu.tiangong.test.TestContext;

/**
 * 循环工作流：start → loop → loop → ... 永不结束。<br>
 * 用于测试最大迭代次数保护。
 */
public class LoopWorkflow {

    @AgentNode(value = "loop-start", type = NodeType.START)
    @NodeNext("loop-body")
    public TestContext start(TestContext ctx) {
        ctx.steps.add("start");
        return ctx;
    }

    @AgentNode("loop-body")
    @NodeNext("loop-body")
    public TestContext loop(TestContext ctx) {
        ctx.steps.add("loop");
        return ctx;
    }
}
