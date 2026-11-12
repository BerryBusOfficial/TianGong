package org.wutong.tiangong.spring.test;

import org.wutong.tiangong.annotations.annotation.framework.Component;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.AgentNode;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.NodeNext;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.entrance.ServiceEntranceNode;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.entrance.ServiceExportNode;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 服务入口 → 服务出口：echo 的回声工作流。
 */
@Component
public class EchoWorkflow {

    public static final List<String> RECEIVED = new CopyOnWriteArrayList<>();

    @AgentNode(value = "echo-start", type = NodeType.START)
    @NodeNext("echo-entrance")
    public TestBridgeContext start(TestBridgeContext ctx) {
        return ctx;
    }

    @ServiceEntranceNode(value = "echo-entrance", url = "agent://test/echo")
    @NodeNext("echo-export")
    public TestBridgeContext echo(TestBridgeContext ctx, EchoCmd cmd) {
        RECEIVED.add("echo:" + cmd.text());
        ctx.setNote(cmd.text());
        return ctx;
    }

    @ServiceExportNode("echo-export")
    @NodeNext("echo-end")
    public EchoReply export(TestBridgeContext ctx) {
        return new EchoReply("pong:" + ctx.getNote());
    }

    /** 服务入口链同样要可达 END（框架启动期的图校验） */
    @AgentNode(value = "echo-end", type = NodeType.END)
    public TestBridgeContext end(TestBridgeContext ctx) {
        return ctx;
    }
}
