package org.wutong.tiangong.test.node;

import org.wutong.tiangong.annotations.annotation.framework.agent.loop.AgentNode;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.NodeNext;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import org.wutong.tiangong.test.TestContext;

/**
 * 简单线性工作流：start → step1 → step2 → end。<br>
 * 验证基本节点跳转和 context 传递。
 */
public class LinearWorkflow {

    @AgentNode(value = "linear-start", type = NodeType.START)
    @NodeNext("linear-step1")
    public TestContext start(TestContext ctx) {
        ctx.steps.add("start");
        return ctx;
    }

    @AgentNode("linear-step1")
    @NodeNext("linear-step2")
    public TestContext step1(TestContext ctx) {
        ctx.steps.add("step1");
        return ctx;
    }

    @AgentNode("linear-step2")
    @NodeNext("linear-end")
    public TestContext step2(TestContext ctx) {
        ctx.steps.add("step2");
        return ctx;
    }

    @AgentNode(value = "linear-end", type = NodeType.END)
    public TestContext end(TestContext ctx) {
        ctx.steps.add("end");
        return ctx;
    }
}
