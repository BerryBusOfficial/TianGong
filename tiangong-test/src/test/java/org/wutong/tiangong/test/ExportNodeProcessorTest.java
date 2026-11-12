package org.wutong.tiangong.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wutong.tiangong.annotations.annotation.definition.BeanDefinition;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.AgentNode;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.NodeNext;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.entrance.ApiExportNode;
import org.wutong.tiangong.common.exception.SystemRuntimeException;
import org.wutong.tiangong.core.agent.AgentNodeRegistry;
import org.wutong.tiangong.core.agent.processor.ExportNodeProcessor;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 出口节点的返回类型约束。<br>
 * <br>
 * <b>这组用例是从真实应用里捞回来的缺陷</b>：{@code @ApiExportNode} 的 javadoc 承诺
 * 「返回值序列化为 JSON 作为 HTTP 响应体」，但 EXPORT 原先归 {@code CommonNodeProcessor} 管，
 * 返回类型被限制成 {@code void / AgentContext} —— 文档承诺的能力<b>一个都写不出来</b>：
 * 出口方法只要返回一个响应对象，容器在 refresh 阶段就拒绝建 Bean，整个应用起不来。
 * 框架自己的单测没抓到，因为测试是直接组装执行器的，不走容器建 Bean 这条路径。
 */
class ExportNodeProcessorTest {

    private AgentNodeRegistry register(Object workflow) {
        AgentNodeRegistry registry = new AgentNodeRegistry();
        new ExportNodeProcessor(registry).process(workflow, BeanDefinition.builder()
                .beanName("workflow")
                .beanClass(workflow.getClass())
                .build());
        return registry;
    }

    @Test
    @DisplayName("出口节点可以返回自定义响应对象")
    void exportMayReturnResponseObject() {
        AgentNodeRegistry registry = register(new PojoExportWorkflow());

        assertTrue(registry.contains("reply"), "响应对象是 EXPORT 最主要的用法，必须允许");
    }

    @Test
    @DisplayName("出口节点可以返回 String（原文回传）")
    void exportMayReturnString() {
        assertTrue(register(new StringExportWorkflow()).contains("reply"));
    }

    @Test
    @DisplayName("出口节点可以返回 void（204 无正文）")
    void exportMayReturnVoid() {
        assertTrue(register(new VoidExportWorkflow()).contains("reply"));
    }

    @Test
    @DisplayName("出口节点不能返回基本类型：作为响应体没有「无值」的表达，且不是合法 JSON 文档")
    void exportRejectsPrimitive() {
        assertThrows(SystemRuntimeException.class, () -> register(new PrimitiveExportWorkflow()),
                "基本类型应在启动期就报错，而不是运行期回一个畸形响应");
    }

    @Test
    @DisplayName("非 EXPORT 节点不归本处理器管：返回类型的约束仍由各自的处理器负责")
    void ignoresNonExportNodes() {
        AgentNodeRegistry registry = register(new CommonWorkflow());

        assertFalse(registry.contains("step"), "COMMON 节点不应被 EXPORT 处理器注册");
    }

    // ==================== 测试用工作流 ====================

    public static class Reply {
        public String text;
        public int code;

        public Reply(String text, int code) {
            this.text = text;
            this.code = code;
        }
    }

    static class PojoExportWorkflow {
        @ApiExportNode
        @NodeNext("end")
        public Reply reply(TestContext ctx) {
            return new Reply("ok", 200);
        }
    }

    static class StringExportWorkflow {
        @ApiExportNode
        public String reply(TestContext ctx) {
            return "{\"result\":\"ok\"}";
        }
    }

    static class VoidExportWorkflow {
        @ApiExportNode
        public void reply(TestContext ctx) {
        }
    }

    static class PrimitiveExportWorkflow {
        @ApiExportNode
        public int reply(TestContext ctx) {
            return 204;
        }
    }

    static class CommonWorkflow {
        @AgentNode
        public String step(TestContext ctx) {
            return "next";
        }
    }
}
