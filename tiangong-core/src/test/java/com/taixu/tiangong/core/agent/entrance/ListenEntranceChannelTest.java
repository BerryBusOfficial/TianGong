package com.taixu.tiangong.core.agent.entrance;

import com.taixu.tiangong.common.agent.context.AgentContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.entrance.ListenNodeHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ListenEntranceChannel} 的行为测试。<br>
 * <br>
 * 重点钉住三件事：① {@code listen()} 是"注册回调后立刻返回"，不是"阻塞等事件"；
 * ② 事件能真的送到入口节点；③ 退出时 {@code dispose()} 一定被调用（外部连接不泄漏）。
 */
class ListenEntranceChannelTest {

    private ListenEntranceChannel channel;

    private ListenEntranceChannel newChannel() {
        channel = new ListenEntranceChannel();
        return channel;
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.close();
        }
    }

    /** 记录投喂口与 dispose 次数的测试 handler */
    private static final class RecordingHandler implements ListenNodeHandler {
        Consumer<Object> emitter;
        int listenCount = 0;
        int disposeCount = 0;

        @Override
        public void listen(AgentContext context,
                           Consumer<Object> emitter) {
            this.emitter = emitter;
            listenCount++;
        }

        @Override
        public void dispose() {
            disposeCount++;
        }
    }

    @Test
    void bindRegistersCallbackAndReturnsImmediately() {
        ListenEntranceChannel ch = newChannel();
        RecordingHandler handler = new RecordingHandler();

        long start = System.currentTimeMillis();
        ch.bind("wf-1", handler, new StubContext("wf-1"));
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(1, handler.listenCount, "bind 必须调一次 listen");
        assertNotNull(handler.emitter, "handler 必须拿到投喂口，否则事件无处可送");
        assertTrue(elapsed < 500,
                "listen() 只注册回调、必须立刻返回（实测耗时 " + elapsed + "ms）");
    }

    @Test
    void emittedPayloadIsDelivered() throws Exception {
        ListenEntranceChannel ch = newChannel();
        RecordingHandler handler = new RecordingHandler();
        ch.bind("wf-2", handler, new StubContext("wf-2"));

        handler.emitter.accept("order-paid");

        Object payload = ch.await("wf-2", new StubContext("wf-2"));
        assertEquals("order-paid", payload);
        assertEquals("\"order-paid\"", ch.toBody(payload), "body 是 JSON：String 也要走引号包起来");
    }

    @Test
    void awaitReturnsNullWhenNotBound() throws Exception {
        ListenEntranceChannel ch = newChannel();
        assertNull(ch.await("wf-none", new StubContext("wf-none")));
    }

    @Test
    void unbindDisposesHandler() throws Exception {
        ListenEntranceChannel ch = newChannel();
        RecordingHandler handler = new RecordingHandler();
        ch.bind("wf-3", handler, new StubContext("wf-3"));

        ch.unbind("wf-3");

        assertEquals(1, handler.disposeCount, "解绑必须 dispose，否则外部连接泄漏");
        assertNull(ch.await("wf-3", new StubContext("wf-3")), "解绑后 await 返回 null");
        assertEquals(0, ch.bindingCount());
    }

    @Test
    void closeDisposesAllBindings() {
        ListenEntranceChannel ch = newChannel();
        RecordingHandler h1 = new RecordingHandler();
        RecordingHandler h2 = new RecordingHandler();
        ch.bind("wf-a", h1, new StubContext("wf-a"));
        ch.bind("wf-b", h2, new StubContext("wf-b"));

        ch.close();

        assertEquals(1, h1.disposeCount);
        assertEquals(1, h2.disposeCount);
        assertEquals(0, ch.bindingCount());
    }

    @Test
    void handlerExceptionLeavesNoBinding() throws Exception {
        ListenEntranceChannel ch = newChannel();
        ListenNodeHandler broken = new ListenNodeHandler() {
            @Override
            public void listen(AgentContext context,
                               Consumer<Object> emitter) {
                throw new IllegalStateException("事件源连不上");
            }
        };

        assertThrows(IllegalStateException.class,
                () -> ch.bind("wf-broken", broken, new StubContext("wf-broken")),
                "listen() 抛异常必须冒泡：吞掉会表现成'永远没事件'，查不出原因");
        assertEquals(0, ch.bindingCount(),
                "listen() 失败时不能留下一个没人投喂的绑定");
        assertNull(ch.await("wf-broken", new StubContext("wf-broken")));
    }

    @Test
    void eventsAreQueuedInOrder() throws Exception {
        ListenEntranceChannel ch = newChannel();
        RecordingHandler handler = new RecordingHandler();
        ch.bind("wf-order", handler, new StubContext("wf-order"));

        List<String> payloads = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            payloads.add("e" + i);
        }
        payloads.forEach(handler.emitter::accept);
        assertEquals(5, ch.pendingCount("wf-order"));

        for (String expected : payloads) {
            assertEquals(expected, ch.await("wf-order", new StubContext("wf-order")));
        }
        assertEquals(0, ch.pendingCount("wf-order"));
    }

    @Test
    void eventsAfterCloseAreDropped() {
        ListenEntranceChannel ch = newChannel();
        RecordingHandler handler = new RecordingHandler();
        ch.bind("wf-closed", handler, new StubContext("wf-closed"));

        ch.close();
        handler.emitter.accept("late-event");

        assertEquals(0, ch.bindingCount());
        // 通道已关，事件直接丢弃 —— 留着只会让队列无限涨（已没有工作流会来取）
        assertEquals(0, ch.pendingCount("wf-closed"));
    }
}
