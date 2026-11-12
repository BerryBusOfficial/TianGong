package org.wutong.tiangong.test;

import org.junit.jupiter.api.Test;
import org.wutong.tiangong.annotations.annotation.framework.agent.interfaces.TriggerPolicyType;
import org.wutong.tiangong.core.agent.AgentLoopExecutor;
import org.wutong.tiangong.core.agent.AgentNodeRegistry;
import org.wutong.tiangong.core.agent.runtime.ResourceOwnershipRegistry;
import org.wutong.tiangong.core.agent.runtime.WorkflowScheduler;
import org.wutong.tiangong.test.node.CronNodeWorkflow;
import org.wutong.tiangong.test.node.ListenNodeWorkflow;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code @CronNode} / {@code @ListenNode} 的端到端回归测试（走真实执行器与节点链）。<br>
 * <br>
 * 通道层已在 core 里单测过；这里专门验证<b>注解 → 执行器分支 → 节点链</b>这条线：
 * 每拍是否真的跑了一整轮、退出时资源是否真的回收、并发名额是否真的还了回去。
 */
class CronListenEntranceTest {

    // ==================== @CronNode ====================

    @Test
    void cronWorkflowRunsOneRoundPerTick() throws Exception {
        AgentLoopExecutor executor = TestHarness.executorFor(new CronNodeWorkflow());
        TestContext ctx = new TestContext();
        ctx.bindWorkflow("wf-cron");   // 定时订阅按 workflowId 记账，必须有 ID

        CompletableFuture<TestContext> future =
                CompletableFuture.supplyAsync(() -> executor.execute("cron-start", ctx));

        Thread.sleep(3200);   // 每秒一拍 → 至少两拍
        ctx.cancel();
        TestContext result = future.get(10, TimeUnit.SECONDS);

        // 每一拍 = 取触发 → 跑到 END；下一拍从入口重新开始
        assertEquals("start", result.steps.get(0));
        assertEquals("tick:1", result.steps.get(1), "第一拍的序号应从 1 开始");
        assertEquals("end", result.steps.get(2));
        long ticks = result.steps.stream().filter(s -> s.startsWith("tick:")).count();
        assertTrue(ticks >= 2, "3.2 秒内应至少触发 2 次，实际 " + ticks + "，轨迹=" + result.steps);
        assertEquals("tick:2", result.steps.get(3), "第二拍紧接在第一轮的 end 之后");
    }

    @Test
    void cronSubscriptionReleasedAfterExit() throws Exception {
        AgentLoopExecutor executor = TestHarness.executorFor(new CronNodeWorkflow());
        TestContext ctx = new TestContext();
        ctx.bindWorkflow("wf-cron2");

        CompletableFuture<TestContext> future =
                CompletableFuture.supplyAsync(() -> executor.execute("cron-start", ctx));
        Thread.sleep(1200);
        ctx.cancel();
        future.get(10, TimeUnit.SECONDS);

        assertEquals(0, executor.cronChannel().subscriptionCount(),
                "退出会话必须退订：否则调度线程会按 cron 一直算下去，而没人再来取触发");
    }

    @Test
    void cronNodeChannelIsRegistered() {
        AgentLoopExecutor executor = TestHarness.executorFor(new CronNodeWorkflow());
        assertTrue(executor.channelRegistry().contains("cron"),
                "定时通道必须按名注册，否则将来按名引用/替换无从谈起");
        assertTrue(executor.channelRegistry().contains("listen"));
    }

    // ==================== @ListenNode ====================

    @Test
    void listenWorkflowRunsOneRoundPerEvent() throws Exception {
        AgentLoopExecutor executor = TestHarness.executorFor(new ListenNodeWorkflow());
        ListenNodeWorkflow.Handler handler = new ListenNodeWorkflow.Handler();
        TestContainer container = new TestContainer();
        container.registerType(ListenNodeWorkflow.Handler.class, handler);
        executor.setContainer(container);

        TestContext ctx = new TestContext();
        ctx.bindWorkflow("wf-listen");

        CompletableFuture<TestContext> future =
                CompletableFuture.supplyAsync(() -> executor.execute("listen-start", ctx));

        awaitUntil(() -> handler.emitter != null, "handler 应已收到投喂口（listen 被调用）");
        assertEquals(1, handler.listenCount, "listen() 只应被调用一次");

        handler.emitter.accept("order-1");
        awaitUntil(() -> ctx.steps.contains("event:order-1"), "第一个事件应被消费");

        handler.emitter.accept("order-2");
        awaitUntil(() -> ctx.steps.contains("event:order-2"), "第二个事件应被消费");

        ctx.cancel();
        TestContext result = future.get(10, TimeUnit.SECONDS);

        assertEquals("[start, event:order-1, end, event:order-2, end]", result.steps.toString(),
                "每个事件跑一轮到 END，然后回到入口等下一个");
        assertEquals(1, handler.disposeCount, "退出时 handler 必须被 dispose（外部连接不泄漏）");
        assertEquals(0, executor.listenChannel().bindingCount());
    }

    @Test
    void listenNodeWithoutContainerFailsLoudly() {
        AgentLoopExecutor executor = TestHarness.executorFor(new ListenNodeWorkflow());
        TestContext ctx = new TestContext();
        ctx.bindWorkflow("wf-nocontainer");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> executor.execute("listen-start", ctx),
                "没有容器时无法解析 listenOn 的 handler —— 必须报错，不能静默跳过");
        assertTrue(ex.getMessage().contains("容器"),
                "异常消息应点名原因，实际: " + ex.getMessage());
    }

    // ==================== 槽与线程解耦 ====================

    @Test
    void parkedEntranceReturnsConcurrencyPermit() throws Exception {
        // 并发上限 = 1：第一条工作流阻塞在监听入口时若不归还名额，
        // 第二条永远起不来 —— 这正是"定时/监听工作流把并发池占死"的复现场景。
        AgentNodeRegistry registry = TestHarness.registryFor(new ListenNodeWorkflow());
        AgentLoopExecutor executor = new AgentLoopExecutor(registry);

        ListenNodeWorkflow.Handler handler = new ListenNodeWorkflow.Handler();
        TestContainer container = new TestContainer();
        container.registerType(ListenNodeWorkflow.Handler.class, handler);
        container.registerType(TestContext.class, new TestContext());
        executor.setContainer(container);

        WorkflowScheduler scheduler = new WorkflowScheduler(
                registry, executor, container, TestContext.class,
                new ResourceOwnershipRegistry(), 1);
        registry.startNodes().forEach(n -> scheduler.registerStartNode(n.name()));
        // 默认 REJECT 会拒绝第二次触发，这里要看的是并发名额，故放开为 PARALLEL
        scheduler.setTriggerPolicy("listen-start", TriggerPolicyType.PARALLEL, null);

        String wf1 = scheduler.startWorkflow("listen-start");
        assertNotNull(wf1);
        awaitUntil(() -> executor.listenChannel().bindingCount() >= 1, "第一条工作流应已进入监听入口");

        String wf2 = scheduler.startWorkflow("listen-start");
        assertNotNull(wf2);

        // 关键断言不是"第二条被受理"（doStart 在线程启动前就返回了 id，
        // 无论有没有名额都会返回非空），而是"它真的跑到了入口"。
        // 名额没还回来时，第二条会一直卡在 globalPermits.acquire() 上，
        // 永远进不到监听入口 —— bindingCount 就停在 1。
        awaitUntil(() -> executor.listenChannel().bindingCount() >= 2,
                "并发上限=1 时第二条工作流也应能进入入口 —— 说明阻塞中的第一条把名额还出来了");

        assertEquals(2, scheduler.listActiveWorkflows().size());
        scheduler.shutdownNow();
    }

    // ==================== 辅助 ====================

    private static void awaitUntil(java.util.function.BooleanSupplier condition, String message) {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail(message + "（等待被中断）");
            }
        }
        if (!condition.getAsBoolean()) {
            fail(message);
        }
    }
}
