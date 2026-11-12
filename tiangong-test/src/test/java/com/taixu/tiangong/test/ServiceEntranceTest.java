package com.taixu.tiangong.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.AgentNode;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeNext;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.entrance.ApiEntranceNode;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.entrance.ApiExportNode;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.entrance.ServiceEntranceNode;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.entrance.ServiceExportNode;
import com.taixu.tiangong.core.agent.AgentLoopExecutor;
import com.taixu.tiangong.core.api.entrance.EntranceResponse;
import com.taixu.tiangong.core.api.entrance.ServiceEntranceException;
import com.taixu.tiangong.core.api.entrance.ServiceEntranceRegistry;
import com.taixu.tiangong.core.api.entrance.ServiceEntranceRoute;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 服务入口（进程内服务调用）端到端测试：注册地址 → {@code call} → fork 执行 →
 * {@code @ServiceExportNode} 回传；含异常类型保真、未注册/歧义/重复注册与 API 退化。<br>
 * <br>
 * 驱动方式与 AgentLoopExecutorTest 一致（TestHarness 手工装配，不启完整框架），
 * 额外把 {@link ServiceEntranceRegistry} 装配给执行器；工作流跑在独立线程里（入口会阻塞驻留）。
 */
class ServiceEntranceTest {

    /** 入口方法实际收到的参数（fork 副本不共享主上下文，用静态收集观测） */
    static final List<String> RECEIVED = new CopyOnWriteArrayList<>();

    private Thread workflowThread;
    private TestContext ctx;

    @BeforeEach
    void reset() {
        RECEIVED.clear();
    }

    @AfterEach
    void stopWorkflow() throws Exception {
        if (ctx != null) {
            ctx.cancel();
        }
        if (workflowThread != null) {
            workflowThread.join(3000);
            assertFalse(workflowThread.isAlive(), "取消后工作流线程应退出（驻留循环 ≤200ms 响应取消）");
        }
    }

    // ==================== 正常调用 ====================

    @Test
    void serviceCallRunsWorkflowAndReturnsExportResult() throws Exception {
        ServiceEntranceRegistry registry = new ServiceEntranceRegistry();
        startWorkflow(registry, new QuoteWorkflow(), "svc-start", "wf-svc", false);
        awaitUrl(registry, "agent://quote/price");

        EntranceResponse response = registry.call("agent://quote/price", null,
                "{\"part\":\"wheel\",\"qty\":3}", 5);

        assertEquals(200, response.status());
        assertNotNull(response.body());
        assertTrue(response.body().contains("\"price\":42"),
                "响应体应是 @ServiceExportNode 的返回值 JSON: " + response.body());
        assertNull(response.cause(), "正常路径不应带异常");
        assertEquals(List.of("wheel"), RECEIVED, "入口方法应收到反序列化后的参数");
    }

    // ==================== 异常类型保真 ====================

    @Test
    void businessExceptionIsCarriedTypeFaithfully() throws Exception {
        ServiceEntranceRegistry registry = new ServiceEntranceRegistry();
        startWorkflow(registry, new FailingWorkflow(), "fail-start", "wf-fail", false);
        awaitUrl(registry, "agent://fail/boom");

        EntranceResponse response = registry.call("agent://fail/boom", null, null, 5);

        assertNotNull(response.cause(), "节点异常必须挂在响应上（供调用方按真实类型 rethrow）");
        assertInstanceOf(IllegalStateException.class, response.cause(), "异常类型必须保真，不被包装");
        assertEquals("工具内部炸了", response.cause().getMessage());
    }

    // ==================== 寻址错误 ====================

    @Test
    void unknownUrlFailsLoudly() {
        ServiceEntranceRegistry registry = new ServiceEntranceRegistry();

        ServiceEntranceException ex = assertThrows(ServiceEntranceException.class,
                () -> registry.call("agent://nope", null, null, 1));
        assertTrue(ex.getMessage().contains("未注册"), ex.getMessage());
    }

    @Test
    void ambiguousUrlRequiresWorkflowParameter() {
        ServiceEntranceRegistry registry = new ServiceEntranceRegistry();
        registry.register(new ServiceEntranceRoute("agent://dup", "wf-a", null, 0));
        registry.register(new ServiceEntranceRoute("agent://dup", "wf-b", null, 0));

        ServiceEntranceException ambiguous = assertThrows(ServiceEntranceException.class,
                () -> registry.call("agent://dup", null, null, 1));
        assertTrue(ambiguous.getMessage().contains("workflow"), ambiguous.getMessage());

        // 指定不存在的工作流：明确报"没有该 workflow 的注册"，而不是歧义
        ServiceEntranceException missing = assertThrows(ServiceEntranceException.class,
                () -> registry.call("agent://dup", "wf-missing", null, 1));
        assertTrue(missing.getMessage().contains("wf-missing"), missing.getMessage());
    }

    @Test
    void duplicateUrlInSameWorkflowFailsAtRegistration() {
        ServiceEntranceRegistry registry = new ServiceEntranceRegistry();
        registry.register(new ServiceEntranceRoute("agent://dup", "wf-a", null, 0));

        assertThrows(ServiceEntranceException.class,
                () -> registry.register(new ServiceEntranceRoute("agent://dup", "wf-a", null, 0)),
                "同一工作流重复注册同一地址是配置错误，必须注册期报错");
    }

    // ==================== API 入口退化 ====================

    @Test
    void apiEntranceDegradesToServiceAddressWhenApiForbidden() throws Exception {
        ServiceEntranceRegistry registry = new ServiceEntranceRegistry();
        startWorkflow(registry, new ApiStyleWorkflow(), "api-start", "wf-degrade", true);
        // 路由 "/demo" → 服务地址 "agent://demo"
        awaitUrl(registry, "agent://demo");

        EntranceResponse response = registry.call("agent://demo", null, "{\"part\":\"x\",\"qty\":1}", 5);

        assertTrue(response.body() != null && response.body().contains("\"price\":7"),
                "@ApiExportNode 在禁用模式下应按服务出口回传: " + response.body());
        assertNull(response.cause());
    }

    // ==================== 驱动辅助 ====================

    private void startWorkflow(ServiceEntranceRegistry registry, Object bean,
                               String startNode, String workflowId, boolean apiForbidden) {
        AgentLoopExecutor executor = new AgentLoopExecutor(TestHarness.registryFor(bean));
        executor.setServiceEntranceRegistry(registry);
        executor.setApiForbidden(apiForbidden);

        ctx = new TestContext();
        ctx.bindWorkflow(workflowId);
        workflowThread = new Thread(() -> executor.execute(startNode, ctx), "svc-test-" + workflowId);
        workflowThread.setDaemon(true);
        workflowThread.start();
    }

    private static void awaitUrl(ServiceEntranceRegistry registry, String url) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (registry.findByUrl(url).isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(registry.findByUrl(url).isEmpty(), "服务地址应在启动后注册: " + url);
    }

    // ==================== 测试工作流 ====================

    /** 服务入口 → 服务出口：出口返回值即调用结果 */
    public static class QuoteWorkflow {

        @AgentNode(value = "svc-start", type = NodeType.START)
        @NodeNext("svc-quote")
        public TestContext start(TestContext ctx) {
            return ctx;
        }

        @ServiceEntranceNode(value = "svc-quote", url = "agent://quote/price")
        @NodeNext("svc-export")
        public TestContext quote(TestContext ctx, QuoteCmd cmd) {
            RECEIVED.add(cmd.part());
            return ctx;
        }

        @ServiceExportNode("svc-export")
        public QuoteResult export(TestContext ctx) {
            return new QuoteResult(42, "ok");
        }
    }

    /** 入口直接抛业务异常：验证类型保真通道 */
    public static class FailingWorkflow {

        @AgentNode(value = "fail-start", type = NodeType.START)
        @NodeNext("fail-entrance")
        public TestContext start(TestContext ctx) {
            return ctx;
        }

        @ServiceEntranceNode(value = "fail-entrance", url = "agent://fail/boom")
        public TestContext entrance(TestContext ctx, QuoteCmd cmd) {
            throw new IllegalStateException("工具内部炸了");
        }
    }

    /** 按 API 入口写的旧工作流：禁用模式下应退化为服务入口 */
    public static class ApiStyleWorkflow {

        @AgentNode(value = "api-start", type = NodeType.START)
        @NodeNext("api-entrance")
        public TestContext start(TestContext ctx) {
            return ctx;
        }

        @ApiEntranceNode(value = "api-entrance", route = "/demo")
        @NodeNext("api-export")
        public TestContext entrance(TestContext ctx, QuoteCmd cmd) {
            RECEIVED.add(cmd.part());
            return ctx;
        }

        @ApiExportNode(value = "api-export", status = 201)
        public QuoteResult export(TestContext ctx) {
            return new QuoteResult(7, "degraded");
        }
    }

    public record QuoteCmd(String part, int qty) {
    }

    public record QuoteResult(int price, String note) {
    }
}
