package org.wutong.tiangong.test.node;

import org.wutong.tiangong.annotations.annotation.framework.agent.loop.AgentNode;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.NodeNext;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.entrance.ListenNode;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.entrance.ListenNodeHandler;
import org.wutong.tiangong.common.agent.context.AgentContext;
import org.wutong.tiangong.test.TestContext;

import java.util.function.Consumer;

/**
 * START → ListenNode → END：用于回归测试 {@code @ListenNode} 的行为。<br>
 * <br>
 * handler 只把 emitter 存下来（模拟"注册回调后立刻返回"），测试线程拿到 emitter
 * 后投事件，工作流跑一轮到 END 再回到入口等下一个事件。
 */
public class ListenNodeWorkflow {

    /** 测试 handler：注册即返回，事件由测试线程投喂 */
    public static class Handler implements ListenNodeHandler {

        public volatile Consumer<Object> emitter;
        public volatile int listenCount;
        public volatile int disposeCount;

        @Override
        public void listen(AgentContext context, Consumer<Object> emitter) {
            this.emitter = emitter;
            listenCount++;
        }

        @Override
        public void dispose() {
            disposeCount++;
        }
    }

    @AgentNode(value = "listen-start", type = NodeType.START)
    @NodeNext("listen-event")
    public TestContext start(TestContext ctx) {
        ctx.steps.add("start");
        return ctx;
    }

    @ListenNode(value = "listen-event", listenOn = Handler.class)
    @NodeNext("listen-end")
    public TestContext onEvent(TestContext ctx, String payload) {
        ctx.steps.add("event:" + payload);
        return ctx;
    }

    @AgentNode(value = "listen-end", type = NodeType.END)
    public TestContext end(TestContext ctx) {
        ctx.steps.add("end");
        return ctx;
    }
}
