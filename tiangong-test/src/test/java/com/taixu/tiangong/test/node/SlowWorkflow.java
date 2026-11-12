package com.taixu.tiangong.test.node;

import com.taixu.tiangong.annotations.annotation.framework.agent.loop.AgentNode;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeNext;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import com.taixu.tiangong.test.TestContext;

/**
 * 慢工作流：start → slow → end。<br>
 * slow 节点循环等待（每 10ms 检查取消标志），用于测试 REJECT 策略和 kill 中断。
 */
public class SlowWorkflow {

    @AgentNode(value = "slow-start", type = NodeType.START)
    @NodeNext("slow-body")
    public TestContext start(TestContext ctx) {
        ctx.steps.add("start");
        return ctx;
    }

    @AgentNode("slow-body")
    @NodeNext("slow-end")
    public TestContext slow(TestContext ctx) {
        ctx.steps.add("slow-begin");
        // 循环等待，定期检查取消标志（协作式中断）
        for (int i = 0; i < 1000; i++) {
            if (ctx.isCancelled()) {
                ctx.steps.add("slow-cancelled");
                return ctx;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ctx.steps.add("slow-interrupted");
                return ctx;
            }
        }
        ctx.steps.add("slow-done");
        return ctx;
    }

    @AgentNode(value = "slow-end", type = NodeType.END)
    public TestContext end(TestContext ctx) {
        ctx.steps.add("end");
        return ctx;
    }
}
