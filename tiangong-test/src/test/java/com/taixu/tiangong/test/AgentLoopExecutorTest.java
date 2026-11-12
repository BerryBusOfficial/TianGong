package com.taixu.tiangong.test;

import com.taixu.tiangong.core.agent.entrance.ApprovalDecision;
import org.junit.jupiter.api.Test;
import com.taixu.tiangong.core.agent.AgentLoopExecutor;
import com.taixu.tiangong.test.node.ApproveNodeWorkflow;
import com.taixu.tiangong.test.node.BranchWorkflow;
import com.taixu.tiangong.test.node.BareEntranceWorkflow;
import com.taixu.tiangong.test.node.LinearWorkflow;
import com.taixu.tiangong.test.node.LoopWorkflow;
import com.taixu.tiangong.test.node.NestedEntranceWorkflow;
import com.taixu.tiangong.test.node.ParallelWorkflow;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AgentLoopExecutor 节点执行引擎测试。<br>
 * 覆盖：线性链、BRANCH 分支、PARALLEL 并发、MERGE 合并、循环保护、指定 START 入口。
 */
class AgentLoopExecutorTest {

    @Test
    void linearChainExecutesInOrder() {
        AgentLoopExecutor executor = TestHarness.executorFor(new LinearWorkflow());
        TestContext ctx = new TestContext();
        TestContext result = executor.execute("linear-start", ctx);

        assertEquals(List.of("start", "step1", "step2", "end"), result.steps);
    }

    @Test
    void branchSelectsAWhenTargetEmpty() {
        AgentLoopExecutor executor = TestHarness.executorFor(new BranchWorkflow());
        TestContext ctx = new TestContext();
        ctx.target = "";
        TestContext result = executor.execute("branch-start", ctx);

        assertTrue(result.steps.contains("branchA"));
        assertFalse(result.steps.contains("branchB"));
        assertTrue(result.steps.contains("end"));
    }

    @Test
    void branchSelectsBWhenTargetNonEmpty() {
        AgentLoopExecutor executor = TestHarness.executorFor(new BranchWorkflow());
        TestContext ctx = new TestContext();
        ctx.target = "goB";
        TestContext result = executor.execute("branch-start", ctx);

        assertTrue(result.steps.contains("branchB"));
        assertFalse(result.steps.contains("branchA"));
    }

    @Test
    void parallelForksAndMerges() {
        AgentLoopExecutor executor = TestHarness.executorFor(new ParallelWorkflow());
        TestContext result = executor.execute("parallel-start", new TestContext());

        assertTrue(result.steps.contains("fork"));
        assertTrue(result.steps.contains("branchA"));
        assertTrue(result.steps.contains("branchB"));
        assertTrue(result.steps.contains("merge"));
        assertTrue(result.steps.contains("end"));
        // 验证 MERGE 收到 2 个分支
        assertTrue(result.steps.contains("branches=2"));
    }

    @Test
    void loopDetectionThrowsAfterMaxIterations() {
        AgentLoopExecutor executor = TestHarness.executorFor(new LoopWorkflow());
        assertThrows(IllegalStateException.class,
                () -> executor.execute("loop-start", new TestContext()),
                "死循环应超过最大迭代次数后抛异常");
    }

    @Test
    void executeWithUnknownStartThrows() {
        AgentLoopExecutor executor = TestHarness.executorFor(new LinearWorkflow());
        assertThrows(IllegalStateException.class,
                () -> executor.execute("nonexistent", new TestContext()),
                "不存在的 START 节点名应抛异常");
    }

    @Test
    void executeWithNonStartNodeThrows() {
        AgentLoopExecutor executor = TestHarness.executorFor(new LinearWorkflow());
        assertThrows(IllegalStateException.class,
                () -> executor.execute("linear-step1", new TestContext()),
                "非 START 类型节点不能作为入口");
    }

    @Test
    void entranceWithoutAnnotationThrows() {
        // START → 裸露 ENTRANCE（无 @ApiEntranceNode / @StdinEntranceNode）必须直接报错，
        // 而不是被静默按普通节点处理（曾经是 log.error 后吞异常的兜底行为）。
        AgentLoopExecutor executor = TestHarness.executorFor(new BareEntranceWorkflow());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> executor.execute("bare-start", new TestContext()),
                "未标注入口注解的 ENTRANCE 应抛 IllegalStateException");
        assertTrue(ex.getMessage().contains("bare-entrance"),
                "异常消息应包含 ENTRANCE 节点名以便定位，实际: " + ex.getMessage());
    }

    @Test
    void nestedEntranceThrows() {
        // 工作流中段遇到 ENTRANCE 节点必须直接报错（设计错误：ENTRANCE 仅可作为入口），
        // 而不是被静默按普通节点处理（曾经的兜底行为，且错误会经 @NodeExceptionAdvice 拦截）。
        AgentLoopExecutor executor = TestHarness.executorFor(new NestedEntranceWorkflow());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> executor.execute("nested-start", new TestContext()),
                "嵌套 ENTRANCE 应抛 IllegalStateException");
        assertTrue(ex.getMessage().contains("nested-entrance"),
                "异常消息应包含嵌套 ENTRANCE 节点名，实际: " + ex.getMessage());
    }

    @Test
    void approveNodeTakesDecisionAndContinues() {
        // WAIT_ONCE：阻塞取一次审批决定 → invoke → 跑后续到 END → 返回
        AgentLoopExecutor executor = TestHarness.executorFor(new ApproveNodeWorkflow());

        // 主线程先发起工作流，async 等审批决定投递后工作流才推进
        java.util.concurrent.CompletableFuture<TestContext> resultFuture =
                java.util.concurrent.CompletableFuture.supplyAsync(() ->
                        executor.execute("approve-start", new TestContext()));

        // 给工作流一点时间进入 await（避免决定投递比 await 更早）
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 投递一次审批决定
        executor.approvalChannel().submit(
                new ApprovalDecision(
                        true, "alice", "looks good"));

        // 等待工作流完成
        TestContext result;
        try {
            result = resultFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("工作流未在超时内完成", e);
        }

        assertEquals(List.of("start", "decision:true:alice", "end"), result.steps,
                "WAIT_ONCE 应阻塞取一次决定后跑后续到 END，再返回（不循环）");
    }
}
