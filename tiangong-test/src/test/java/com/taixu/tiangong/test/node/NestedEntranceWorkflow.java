package com.taixu.tiangong.test.node;

import com.taixu.tiangong.annotations.annotation.framework.agent.loop.AgentNode;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeNext;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import com.taixu.tiangong.test.TestContext;

/**
 * START → middle → 嵌套 ENTRANCE（无入口注解）。用于回归测试 ——
 * 验证工作流中段再遇到 ENTRANCE 节点会被框架直接报错，
 * 而不是被静默按普通节点处理（曾经的兜底行为）。
 *
 * <p>触发路径：execute("nested-start") → doExecute 循环执行 start → advance 到
 * middle → advance 到 nested-entrance → 当前节点类型 ENTRANCE → 工作流中段遇到 ENTRANCE
 * 直接抛 IllegalStateException。
 */
public class NestedEntranceWorkflow {

    @AgentNode(value = "nested-start", type = NodeType.START)
    @NodeNext("nested-middle")
    public TestContext start(TestContext ctx) {
        ctx.steps.add("start");
        return ctx;
    }

    @AgentNode("nested-middle")
    @NodeNext("nested-entrance")
    public TestContext middle(TestContext ctx) {
        ctx.steps.add("middle");
        return ctx;
    }

    @AgentNode(value = "nested-entrance", type = NodeType.ENTRANCE)
    public TestContext entrance(TestContext ctx) {
        ctx.steps.add("entrance");
        return ctx;
    }
}