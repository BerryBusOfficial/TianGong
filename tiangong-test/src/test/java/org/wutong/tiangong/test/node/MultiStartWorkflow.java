package org.wutong.tiangong.test.node;

import org.wutong.tiangong.annotations.annotation.framework.agent.loop.AgentNode;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.NodeNext;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import org.wutong.tiangong.test.TestContext;

/**
 * 多 START 工作流：两条独立线性流共享中间节点。<br>
 * start1 → shared → end1<br>
 * start2 → shared → end2<br>
 * 用于测试多 START 并发启动。
 */
public class MultiStartWorkflow {

    @AgentNode(value = "multi-start1", type = NodeType.START)
    @NodeNext("multi-shared")
    public TestContext start1(TestContext ctx) {
        ctx.steps.add("start1");
        return ctx;
    }

    @AgentNode(value = "multi-start2", type = NodeType.START)
    @NodeNext("multi-shared")
    public TestContext start2(TestContext ctx) {
        ctx.steps.add("start2");
        return ctx;
    }

    @AgentNode("multi-shared")
    @NodeNext("multi-end")
    public TestContext shared(TestContext ctx) {
        ctx.steps.add("shared");
        return ctx;
    }

    @AgentNode(value = "multi-end", type = NodeType.END)
    public TestContext end(TestContext ctx) {
        ctx.steps.add("end");
        return ctx;
    }
}
