package org.wutong.tiangong.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.AgentNode;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.NodeNext;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.tool.ToolNode;
import org.wutong.tiangong.annotations.annotation.framework.inject.Inject;
import org.wutong.tiangong.common.exception.SystemRuntimeException;
import org.wutong.tiangong.core.agent.AgentLoopExecutor;
import org.wutong.tiangong.core.agent.AgentToolInvoker;
import org.wutong.tiangong.core.container.context.framework.ApplicationFrameworkContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TOOL 节点与 {@link AgentToolInvoker} 参数注入的端到端验证。<br>
 * <br>
 * 必须用<b>真实上下文</b>而非 {@link TestHarness}：注入来源是容器 Bean，
 * 而 {@code TestHarness} 手动装配节点、不启动容器，
 * {@code ContextHolder.getApplication()} 为 null，注入将退化为 null。
 */
@TianGongApplicationTestStarter
class ToolNodeInjectionTest {

    @Inject
    ApplicationFrameworkContext context;

    @Test
    @DisplayName("TOOL 节点：AgentToolInvoker 作为形参由框架注入，且不依赖字段 @Inject")
    void toolNodeReceivesInvokerAsParameter() {
        ToolWorkflow.CAPTURED = null;
        AgentLoopExecutor executor = TestHarness.executorFor(new ToolWorkflow());

        executor.execute("tool-start", new TestContext());

        assertNotNull(ToolWorkflow.CAPTURED,
                "TOOL 节点方法应收到框架注入的 AgentToolInvoker；"
                        + "为 null 说明参数注入未生效，用户方法会在调用工具时 NPE");
    }

    @Test
    @DisplayName("注入的是容器里那个 Bean（同一引用，不是新建的）")
    void injectedInvokerIsTheContainerBean() {
        ToolWorkflow.CAPTURED = null;
        AgentLoopExecutor executor = TestHarness.executorFor(new ToolWorkflow());

        executor.execute("tool-start", new TestContext());

        AgentToolInvoker bean = context.getContainer().getBean(AgentToolInvoker.class);
        assertNotNull(bean, "AgentToolInvoker 标了 @Component，容器里应有它的 Bean");
        assertSame(bean, ToolWorkflow.CAPTURED,
                "注入的应是容器管理的那个实例，保证 FunctionRegistry 等依赖一致");
    }

    @Test
    @DisplayName("通用性：非 TOOL 节点（COMMON）声明该形参同样能收到")
    void injectionWorksOnCommonNodeToo() {
        CommonInvokerWorkflow.CAPTURED = null;
        AgentLoopExecutor executor = TestHarness.executorFor(new CommonInvokerWorkflow());

        executor.execute("common-start", new TestContext());

        assertNotNull(CommonInvokerWorkflow.CAPTURED,
                "参数注入是通用机制，不该只有 TOOL 节点能用；"
                        + "TOOL 只是加了强制校验与语义标记");
    }

    @Test
    @DisplayName("TOOL 节点缺少 AgentToolInvoker 形参 → 注册期报错")
    void toolNodeWithoutInvokerFailsAtRegistration() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> TestHarness.registryFor(new BadToolWorkflow()),
                "签名不含调用器的 TOOL 节点应在注册阶段就失败，而不是执行时拿到 null");

        assertTrue(ex.getMessage().contains("AgentToolInvoker"),
                "异常信息应点明缺少 AgentToolInvoker，实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("TOOL 节点声明多个调用器或多个上下文 → 注册期报错")
    void toolNodeWithDuplicateParamsFails() {
        assertThrows(RuntimeException.class,
                () -> TestHarness.registryFor(new DuplicateToolWorkflow()));
    }

    // ==================== 测试用工作流 ====================

    /** 正常 TOOL：START → TOOL（注入调用器）→ END */
    public static class ToolWorkflow {

        static AgentToolInvoker CAPTURED;

        @AgentNode(value = "tool-start", type = NodeType.START)
        @NodeNext("call-tool")
        public void start(TestContext ctx) {
            // 仅用于占入口位置
        }

        @ToolNode("call-tool")
        @NodeNext("done")
        public void callTool(TestContext ctx, AgentToolInvoker invoker) {
            CAPTURED = invoker;
        }

        @AgentNode(value = "done", type = NodeType.END)
        public void done(TestContext ctx) {
        }
    }

    /** 对照组：普通 COMMON 节点声明同样形参，验证注入能力不绑定 TOOL 类型 */
    public static class CommonInvokerWorkflow {

        static AgentToolInvoker CAPTURED;

        @AgentNode(value = "common-start", type = NodeType.START)
        @NodeNext("call-tool")
        public void start(TestContext ctx) {
        }

        @AgentNode("call-tool")
        @NodeNext("done")
        public void callTool(TestContext ctx, AgentToolInvoker invoker) {
            CAPTURED = invoker;
        }

        @AgentNode(value = "done", type = NodeType.END)
        public void done(TestContext ctx) {
        }
    }

    /** 缺调用器的 TOOL：应被 ToolNodeProcessor 拦下 */
    public static class BadToolWorkflow {

        @AgentNode(value = "bad-start", type = NodeType.START)
        @NodeNext("bad-tool")
        public void start(TestContext ctx) {
        }

        @ToolNode("bad-tool")
        public void badTool(TestContext ctx) {
            // 缺少 AgentToolInvoker
        }
    }

    /** 重复声明：两个调用器 */
    public static class DuplicateToolWorkflow {

        @AgentNode(value = "dup-start", type = NodeType.START)
        @NodeNext("dup-tool")
        public void start(TestContext ctx) {
        }

        @ToolNode("dup-tool")
        public void dupTool(TestContext ctx, AgentToolInvoker a, AgentToolInvoker b) {
        }
    }
}
