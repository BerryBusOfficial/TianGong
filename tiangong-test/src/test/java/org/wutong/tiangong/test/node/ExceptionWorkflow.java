package org.wutong.tiangong.test.node;

import org.wutong.tiangong.annotations.annotation.framework.agent.loop.AgentNode;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.NodeNext;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import org.wutong.tiangong.test.TestContext;

/**
 * 异常工作流：start → risky → end。<br>
 * risky 节点抛出 IllegalStateException，由 advice 捕获跳转到 recover。
 */
public class ExceptionWorkflow {

    @AgentNode(value = "exc-start", type = NodeType.START)
    @NodeNext("exc-risky")
    public TestContext start(TestContext ctx) {
        ctx.steps.add("start");
        return ctx;
    }

    @AgentNode("exc-risky")
    @NodeNext("exc-end")
    public TestContext risky(TestContext ctx) {
        ctx.steps.add("risky");
        throw new IllegalStateException("risky boom");
    }

    @AgentNode("exc-recover")
    @NodeNext("exc-end")
    public TestContext recover(TestContext ctx) {
        ctx.steps.add("recover");
        return ctx;
    }

    @AgentNode(value = "exc-end", type = NodeType.END)
    public TestContext end(TestContext ctx) {
        ctx.steps.add("end");
        return ctx;
    }
}
