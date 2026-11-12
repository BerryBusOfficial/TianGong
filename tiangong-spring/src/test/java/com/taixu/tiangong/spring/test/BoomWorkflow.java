package com.taixu.tiangong.spring.test;

import com.taixu.tiangong.annotations.annotation.framework.Component;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.AgentNode;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeNext;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.entrance.ServiceEntranceNode;

/**
 * 入口直接抛业务异常：验证异常跨桥后的类型保真。
 */
@Component
public class BoomWorkflow {

    @AgentNode(value = "boom-start", type = NodeType.START)
    @NodeNext("boom-entrance")
    public TestBridgeContext start(TestBridgeContext ctx) {
        return ctx;
    }

    @ServiceEntranceNode(value = "boom-entrance", url = "agent://test/boom")
    @NodeNext("boom-end")
    public void boom(TestBridgeContext ctx, EchoCmd cmd) {
        throw new BridgeBizException("boom!");
    }

    @AgentNode(value = "boom-end", type = NodeType.END)
    public TestBridgeContext end(TestBridgeContext ctx) {
        return ctx;
    }
}
