package org.wutong.tiangong.test;

import org.junit.jupiter.api.Test;
import org.wutong.tiangong.core.agent.AgentLoopExecutor;
import org.wutong.tiangong.core.agent.AgentNodeRegistry;
import org.wutong.tiangong.core.agent.runtime.ResourceOwnershipRegistry;
import org.wutong.tiangong.core.agent.runtime.WorkflowInstance;
import org.wutong.tiangong.core.agent.runtime.WorkflowScheduler;
import org.wutong.tiangong.test.node.LinearWorkflow;
import org.wutong.tiangong.test.node.MultiStartWorkflow;
import org.wutong.tiangong.test.node.SlowWorkflow;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WorkflowScheduler 多工作流调度器测试。<br>
 * 覆盖：单工作流启动完成、多 START 并发、REJECT 策略、kill、listActiveWorkflows。
 */
class WorkflowSchedulerTest {

    private WorkflowScheduler schedulerFor(Object... beans) {
        AgentNodeRegistry registry = TestHarness.registryFor(beans);
        AgentLoopExecutor executor = new AgentLoopExecutor(registry);
        TestContainer container = new TestContainer();
        container.registerType(TestContext.class, new TestContext());  // 用于 getBean 创建 context
        ResourceOwnershipRegistry resReg = new ResourceOwnershipRegistry();
        WorkflowScheduler scheduler = new WorkflowScheduler(
                registry, executor, container, TestContext.class, resReg, 8);
        // 注册 START 节点
        registry.startNodes().forEach(n -> scheduler.registerStartNode(n.name()));
        return scheduler;
    }

    @Test
    void singleWorkflowCompletes() throws Exception {
        WorkflowScheduler scheduler = schedulerFor(new LinearWorkflow());
        String wfId = scheduler.startWorkflow("linear-start");
        assertNotNull(wfId);
        // 等待虚拟线程完成
        Thread.sleep(500);
        List<WorkflowInstance> active = scheduler.listActiveWorkflows();
        assertTrue(active.isEmpty(), "工作流应已完成");
    }

    @Test
    void multipleStartsConcurrently() throws Exception {
        WorkflowScheduler scheduler = schedulerFor(new MultiStartWorkflow());
        String wf1 = scheduler.startWorkflow("multi-start1");
        String wf2 = scheduler.startWorkflow("multi-start2");
        assertNotNull(wf1);
        assertNotNull(wf2);
        assertNotEquals(wf1, wf2);
        Thread.sleep(500);
        assertTrue(scheduler.listActiveWorkflows().isEmpty(), "两条工作流都应已完成");
    }

    @Test
    void rejectPolicyReturnsNullWhenActive() throws Exception {
        // 默认 REJECT 策略：运行中再触发返回 null
        WorkflowScheduler scheduler = schedulerFor(new SlowWorkflow());
        String wf1 = scheduler.startWorkflow("slow-start");
        assertNotNull(wf1);
        Thread.sleep(200);  // 确保工作流在运行中（slow 节点 sleep 10s）
        // 再次触发同一 START，REJECT 应返回 null
        String wf2 = scheduler.startWorkflow("slow-start");
        assertNull(wf2, "REJECT 策略应拒绝运行中的新触发");
        // 清理
        scheduler.kill(wf1);
    }

    @Test
    void killCancelsRunningWorkflow() throws Exception {
        WorkflowScheduler scheduler = schedulerFor(new SlowWorkflow());
        String wfId = scheduler.startWorkflow("slow-start");
        Thread.sleep(200);  // 确保 slow 节点在运行
        boolean killed = scheduler.kill(wfId);
        assertTrue(killed, "kill 请求应发送成功");
        Thread.sleep(500);
        // 工作流应已取消退出（cancelFlag 在下一节点边界检查后抛 WorkflowCancelledException）
        assertTrue(scheduler.listActiveWorkflows().isEmpty(),
                "被 kill 的工作流应已退出");
    }

    @Test
    void unregisteredStartThrows() {
        WorkflowScheduler scheduler = schedulerFor(new LinearWorkflow());
        assertThrows(IllegalStateException.class,
                () -> scheduler.startWorkflow("nonexistent"),
                "未注册的 START 节点应抛异常");
    }
}
