package org.wutong.tiangong.test.node;

import org.wutong.tiangong.annotations.annotation.framework.agent.loop.AgentNode;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.NodeNext;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import org.wutong.tiangong.common.agent.context.AgentContext;
import org.wutong.tiangong.test.TestContext;

import java.util.List;

/**
 * 并行工作流：start → parallel → (branchA | branchB) → merge → end。<br>
 * PARALLEL 节点 fork 虚拟线程并发执行两个分支，MERGE 收集结果。
 */
public class ParallelWorkflow {

    @AgentNode(value = "parallel-start", type = NodeType.START)
    @NodeNext("parallel-fork")
    public TestContext start(TestContext ctx) {
        ctx.steps.add("start");
        return ctx;
    }

    @AgentNode(value = "parallel-fork", type = NodeType.PARALLEL)
    @NodeNext({"parallel-a", "parallel-b"})
    public TestContext fork(TestContext ctx) {
        ctx.steps.add("fork");
        return ctx;
    }

    @AgentNode("parallel-a")
    public TestContext branchA(TestContext ctx) {
        ctx.steps.add("branchA");
        return ctx;
    }

    @AgentNode("parallel-b")
    public TestContext branchB(TestContext ctx) {
        ctx.steps.add("branchB");
        return ctx;
    }

    @AgentNode(value = "parallel-merge", type = NodeType.MERGE)
    @NodeNext("parallel-end")
    public TestContext merge(TestContext ctx, List<AgentContext> branches) {
        ctx.steps.add("merge");
        ctx.steps.add("branches=" + branches.size());
        // 合并分支副本的 steps 到主 context
        for (AgentContext branch : branches) {
            if (branch instanceof TestContext tc) {
                ctx.steps.addAll(tc.steps);
            }
        }
        return ctx;
    }

    @AgentNode(value = "parallel-end", type = NodeType.END)
    public TestContext end(TestContext ctx) {
        ctx.steps.add("end");
        return ctx;
    }
}
