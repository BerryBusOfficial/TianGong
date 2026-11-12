package com.taixu.tiangong.test.node;

import com.taixu.tiangong.annotations.annotation.framework.agent.loop.AgentNode;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeNext;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.entrance.CronNode;
import com.taixu.tiangong.core.agent.entrance.CronTick;
import com.taixu.tiangong.test.TestContext;

/**
 * START → CronNode → END：用于回归测试 {@code @CronNode} 的 SESSION_LOOP 行为。<br>
 * <br>
 * 每秒触发一次，每一拍跑一轮到 END 再回到入口等下一拍。
 * 测试跑几秒后 {@code ctx.cancel()} 退出会话。
 */
public class CronNodeWorkflow {

    @AgentNode(value = "cron-start", type = NodeType.START)
    @NodeNext("cron-tick")
    public TestContext start(TestContext ctx) {
        ctx.steps.add("start");
        return ctx;
    }

    @CronNode(value = "cron-tick", cron = "* * * * * ?", zone = "UTC")
    @NodeNext("cron-end")
    public TestContext onTick(TestContext ctx, CronTick tick) {
        ctx.steps.add("tick:" + tick.sequence());
        return ctx;
    }

    @AgentNode(value = "cron-end", type = NodeType.END)
    public TestContext end(TestContext ctx) {
        ctx.steps.add("end");
        return ctx;
    }
}
