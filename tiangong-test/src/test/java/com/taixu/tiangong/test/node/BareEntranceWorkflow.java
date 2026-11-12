package com.taixu.tiangong.test.node;

import com.taixu.tiangong.annotations.annotation.framework.agent.loop.AgentNode;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeNext;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import com.taixu.tiangong.test.TestContext;

/**
 * START → 裸露 ENTRANCE 的工作流：用于回归测试 —— 验证未标注入口注解
 * （@ApiEntranceNode / @StdinEntranceNode）的 ENTRANCE 节点会被框架直接报错，
 * 而不是被静默按普通节点处理（曾经的兜底行为）。
 *
 * <p>触发路径：execute("bare-start", ctx) → doExecute → 循环里识别到 ENTRANCE
 * 类型 → handleEntrance → 未找到入口注解 → 抛 IllegalStateException。
 */
public class BareEntranceWorkflow {

    @AgentNode(value = "bare-start", type = NodeType.START)
    @NodeNext("bare-entrance")
    public TestContext start(TestContext ctx) {
        ctx.steps.add("start");
        return ctx;
    }

    @AgentNode(value = "bare-entrance", type = NodeType.ENTRANCE)
    public TestContext entrance(TestContext ctx) {
        ctx.steps.add("entrance");
        return ctx;
    }
}