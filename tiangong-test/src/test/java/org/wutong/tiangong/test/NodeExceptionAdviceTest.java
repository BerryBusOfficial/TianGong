package org.wutong.tiangong.test;

import org.junit.jupiter.api.Test;
import org.wutong.tiangong.annotations.annotation.definition.BeanDefinition;
import org.wutong.tiangong.annotations.annotation.definition.Lifecycle;
import org.wutong.tiangong.annotations.annotation.framework.agent.AgentNodeExceptionAdvice;
import org.wutong.tiangong.annotations.annotation.framework.agent.NodeExceptionHandler;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.AgentNode;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.NodeNext;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import org.wutong.tiangong.core.agent.AgentLoopExecutor;
import org.wutong.tiangong.core.agent.NodeExceptionAdviceProcessor;
import org.wutong.tiangong.core.agent.NodeExceptionAdviceRegistry;
import org.wutong.tiangong.test.TestContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 节点异常处理测试。<br>
 * 覆盖：全局 advice 捕获并跳转、未匹配异常原样传播。
 */
class NodeExceptionAdviceTest {

    /** 全局 advice：捕获 IllegalStateException → 跳转 recover */
    @AgentNodeExceptionAdvice
    static class GlobalAdvice {
        @NodeExceptionHandler(exception = IllegalStateException.class)
        public String handleIllegal(IllegalStateException e) {
            return "exc-recover";
        }
    }

    private NodeExceptionAdviceRegistry registryFor(Object... advices) {
        NodeExceptionAdviceRegistry reg = new NodeExceptionAdviceRegistry();
        NodeExceptionAdviceProcessor processor = new NodeExceptionAdviceProcessor(reg);
        for (Object advice : advices) {
            processor.process(advice, BeanDefinition.builder()
                    .beanName(advice.getClass().getSimpleName())
                    .beanClass(advice.getClass())
                    .lifecycle(Lifecycle.SINGLETON)
                    .lazyInit(false)
                    .build());
        }
        return reg;
    }

    @Test
    void globalAdviceCatchesAndJumps() {
        NodeExceptionAdviceRegistry advice = registryFor(new GlobalAdvice());
        AgentLoopExecutor executor = TestHarness.executorWithAdvice(advice, new ExceptionWorkflow());
        TestContext result = executor.execute("exc-start", new TestContext());
        assertTrue(result.steps.contains("risky"));
        assertTrue(result.steps.contains("recover"));
        assertTrue(result.steps.contains("end"));
    }

    @Test
    void uncaughtExceptionPropagates() {
        NodeExceptionAdviceRegistry advice = new NodeExceptionAdviceRegistry();
        AgentLoopExecutor executor = TestHarness.executorWithAdvice(advice, new ExceptionWorkflow());
        assertThrows(IllegalStateException.class,
                () -> executor.execute("exc-start", new TestContext()),
                "无匹配 advice 的异常应原样传播");
    }

    /**
     * 测试用异常工作流：start → risky → end<br>
     * risky 抛 IllegalStateException，GlobalAdvice 捕获跳转 recover。
     */
    static class ExceptionWorkflow {
        @AgentNode(value = "exc-start", type = NodeType.START)
        @NodeNext("exc-risky")
        public TestContext start(TestContext ctx) {
            ctx.steps.add("start");
            return ctx;
        }

        @AgentNode("exc-risky")
        @NodeNext("exc-end")
        public TestContext risky(TestContext ctx) {
            ctx.steps.add("risky");
            throw new IllegalStateException("boom");
        }

        @AgentNode("exc-recover")
        @NodeNext("exc-end")
        public TestContext recover(TestContext ctx) {
            ctx.steps.add("recover");
            return ctx;
        }

        @AgentNode(value = "exc-end", type = NodeType.END)
        public TestContext end(TestContext ctx) {
            ctx.steps.add("end");
            return ctx;
        }
    }
}
