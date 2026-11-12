package com.taixu.tiangong.test.node;

import com.taixu.tiangong.annotations.annotation.framework.agent.loop.AgentNode;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeNext;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import com.taixu.tiangong.test.TestContext;

/**
 * 分支工作流：start → branch → (branchA | branchB) → end。<br>
 * BRANCH 节点根据 context.target 决定走 A 还是 B。
 */
public class BranchWorkflow {

    @AgentNode(value = "branch-start", type = NodeType.START)
    @NodeNext("branch-decide")
    public TestContext start(TestContext ctx) {
        ctx.steps.add("start");
        return ctx;
    }

    /** BRANCH：target 非空走 B，空走 A */
    @AgentNode(value = "branch-decide", type = NodeType.BRANCH)
    @NodeNext({"branchA", "branchB"})
    public String decide(TestContext ctx) {
        ctx.steps.add("decide");
        return ctx.target.isEmpty() ? "branchA" : "branchB";
    }

    @AgentNode("branchA")
    @NodeNext("branch-end")
    public TestContext branchA(TestContext ctx) {
        ctx.steps.add("branchA");
        return ctx;
    }

    @AgentNode("branchB")
    @NodeNext("branch-end")
    public TestContext branchB(TestContext ctx) {
        ctx.steps.add("branchB");
        return ctx;
    }

    @AgentNode(value = "branch-end", type = NodeType.END)
    public TestContext end(TestContext ctx) {
        ctx.steps.add("end");
        return ctx;
    }
}
