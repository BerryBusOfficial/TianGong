package com.taixu.tiangong.core.container.process.impl;

import com.taixu.tiangong.annotations.annotation.definition.BeanDefinition;
import com.taixu.tiangong.annotations.annotation.framework.agent.AgentFunction;
import com.taixu.tiangong.annotations.annotation.framework.agent.AgentTool;
import com.taixu.tiangong.common.agent.call.AgentCallContext;
import com.taixu.tiangong.core.function.AgentFunctionDefinition;
import com.taixu.tiangong.core.function.FunctionRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentFunctionProcessorTest {

    @Test
    void mergesClassAndMethodScopesAndSkipsCallContextParameter() throws Exception {
        FunctionRegistry registry = new FunctionRegistry();
        AgentFunctionProcessor processor = new AgentFunctionProcessor(registry);
        BeanDefinition definition = BeanDefinition.builder()
                .beanName("reportTools")
                .beanClass(ReportTools.class)
                .build();
        processor.process(new ReportTools(), definition);

        AgentFunctionDefinition tool = registry.get("draft_report").orElseThrow();
        assertEquals(Set.of("report:read", "report:draft"), tool.getScopes());
        assertTrue(tool.isRequiresApproval());
        assertEquals(1, tool.getParams().size(), "AgentCallContext 不应进入工具参数 schema");
        assertEquals("text", tool.getParams().getFirst().name());

        AgentCallContext context = new AgentCallContext("u-1", "t-1", Set.of("report:*"), "trace");
        String result = AgentCallContext.callWith(context,
                () -> (String) tool.invoke(Map.of("text", "hello")));
        assertEquals("u-1:hello", result);
    }

    @AgentTool(scopes = {"report:read"})
    static class ReportTools {

        @AgentFunction(name = "draft_report", description = "起草报告",
                scopes = {"report:draft"}, requiresApproval = true)
        String draft(@AgentFunction.ToolParam(name = "text", description = "正文") String text,
                     AgentCallContext context) {
            return (context == null ? "anonymous" : context.userId()) + ":" + text;
        }
    }
}
