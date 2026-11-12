package com.taixu.tiangong.spring.test;

import com.taixu.tiangong.annotations.annotation.framework.Component;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.AgentNode;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeNext;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.entrance.ServiceEntranceNode;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * void 服务入口：验证"受理即返回"路径。
 */
@Component
public class PingWorkflow {

    public static final List<String> PINGED = new CopyOnWriteArrayList<>();

    @AgentNode(value = "ping-start", type = NodeType.START)
    @NodeNext("ping-entrance")
    public TestBridgeContext start(TestBridgeContext ctx) {
        return ctx;
    }

    @ServiceEntranceNode(value = "ping-entrance", url = "agent://test/ping")
    @NodeNext("ping-end")
    public void ping(TestBridgeContext ctx, EchoCmd cmd) {
        PingWorkflow.PINGED.add(cmd.text());
    }

    @AgentNode(value = "ping-end", type = NodeType.END)
    public TestBridgeContext end(TestBridgeContext ctx) {
        return ctx;
    }
}
