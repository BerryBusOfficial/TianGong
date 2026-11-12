package com.taixu.tiangong.spring.test;

import com.taixu.tiangong.annotations.annotation.framework.Component;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.AgentNode;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeNext;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.entrance.ServiceEntranceNode;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.entrance.ServiceExportNode;
import com.taixu.tiangong.spring.annotation.SpringWrite;

/**
 * 实例桥（Spring → 天工方向）：天工节点里用 {@code @SpringWrite} 直接持有 Spring 服务。
 */
@Component
public class SpringWriteWorkflow {

    @SpringWrite
    private SpringEchoService springEchoService;

    @AgentNode(value = "spring-write-start", type = NodeType.START)
    @NodeNext("spring-write-entrance")
    public TestBridgeContext start(TestBridgeContext ctx) {
        return ctx;
    }

    @ServiceEntranceNode(value = "spring-write-entrance", url = "agent://test/spring-echo")
    @NodeNext("spring-write-export")
    public TestBridgeContext entrance(TestBridgeContext ctx, EchoCmd cmd) {
        ctx.setNote(springEchoService.decorate(cmd.text()));
        return ctx;
    }

    @ServiceExportNode("spring-write-export")
    @NodeNext("spring-write-end")
    public EchoReply export(TestBridgeContext ctx) {
        return new EchoReply(ctx.getNote());
    }

    @AgentNode(value = "spring-write-end", type = NodeType.END)
    public TestBridgeContext end(TestBridgeContext ctx) {
        return ctx;
    }
}
