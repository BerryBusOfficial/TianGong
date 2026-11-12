package org.wutong.tiangong.test;

import org.junit.jupiter.api.Test;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.AgentNode;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.NodeNext;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import org.wutong.tiangong.common.exception.SystemRuntimeException;
import org.wutong.tiangong.core.agent.AgentNodeRegistry;
import org.wutong.tiangong.core.agent.processor.AgentNodeAnnotationProcessor;
import org.wutong.tiangong.core.agent.runtime.WorkflowGraphValidator;
import org.wutong.tiangong.test.TestContext;
import org.wutong.tiangong.test.node.LinearWorkflow;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WorkflowGraphValidator 启动期工作流图校验测试。<br>
 * 覆盖：无 START、引用不存在节点、BRANCH next<2、PARALLEL next<2、END 有 next、START 不可达 END。
 */
class WorkflowGraphValidatorTest {

    @Test
    void validLinearGraphPasses() {
        AgentNodeRegistry reg = TestHarness.registryFor(new LinearWorkflow());
        assertDoesNotThrow(() -> WorkflowGraphValidator.validate(reg));
    }

    @Test
    void noStartNodeThrows() {
        AgentNodeRegistry reg = TestHarness.registryFor(new NoStartWorkflow());
        assertThrows(SystemRuntimeException.class,
                () -> WorkflowGraphValidator.validate(reg),
                "无 START 节点应校验失败");
    }

    @Test
    void danglingNextReferenceThrows() {
        AgentNodeRegistry reg = TestHarness.registryFor(new DanglingNextWorkflow());
        assertThrows(SystemRuntimeException.class,
                () -> WorkflowGraphValidator.validate(reg),
                "@NodeNext 指向不存在节点应校验失败");
    }

    @Test
    void branchWithSingleNextThrows() {
        // NodeProcessor 在注册阶段即校验 BRANCH next>=2
        assertThrows(SystemRuntimeException.class,
                () -> TestHarness.registryFor(new BranchSingleNextWorkflow()),
                "BRANCH 节点 next<2 应在注册阶段被拒");
    }

    @Test
    void endWithNextThrows() {
        // NodeProcessor 在注册阶段即校验 END 不允许 @NodeNext
        assertThrows(SystemRuntimeException.class,
                () -> TestHarness.registryFor(new EndWithNextWorkflow()),
                "END 节点有 @NodeNext 应在注册阶段被拒");
    }

    @Test
    void startCannotReachEndThrows() {
        AgentNodeRegistry reg = TestHarness.registryFor(new UnreachableEndWorkflow());
        assertThrows(SystemRuntimeException.class,
                () -> WorkflowGraphValidator.validate(reg),
                "START 不可达 END 应校验失败");
    }

    // ====== 错误图定义 ======

    /** 无 START 节点 */
    static class NoStartWorkflow {
        @AgentNode(value = "nstart-a", type = NodeType.COMMON)
        @NodeNext("nstart-b")
        public void a(TestContext ctx) {}
        @AgentNode(value = "nstart-b", type = NodeType.END)
        public void b(TestContext ctx) {}
    }

    /** @NodeNext 指向不存在的节点 */
    static class DanglingNextWorkflow {
        @AgentNode(value = "dangle-start", type = NodeType.START)
        @NodeNext("dangle-missing")
        public void a(TestContext ctx) {}
    }

    /** BRANCH 节点只有 1 个 next */
    static class BranchSingleNextWorkflow {
        @AgentNode(value = "bsingle-start", type = NodeType.START)
        @NodeNext("bsingle-decide")
        public void a(TestContext ctx) {}
        @AgentNode(value = "bsingle-decide", type = NodeType.BRANCH)
        @NodeNext("bsingle-end")
        public String b(TestContext ctx) { return "bsingle-end"; }
        @AgentNode(value = "bsingle-end", type = NodeType.END)
        public void c(TestContext ctx) {}
    }

    /** END 节点有 @NodeNext */
    static class EndWithNextWorkflow {
        @AgentNode(value = "ewn-start", type = NodeType.START)
        @NodeNext("ewn-end")
        public void a(TestContext ctx) {}
        @AgentNode(value = "ewn-end", type = NodeType.END)
        @NodeNext("ewn-missing")
        public void b(TestContext ctx) {}
    }

    /** START 不可达 END（无 END 节点） */
    static class UnreachableEndWorkflow {
        @AgentNode(value = "unreach-start", type = NodeType.START)
        @NodeNext("unreach-mid")
        public void a(TestContext ctx) {}
        @AgentNode("unreach-mid")
        public void b(TestContext ctx) {}
    }
}
