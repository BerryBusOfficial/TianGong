package com.taixu.tiangong.core.function;

import com.taixu.tiangong.common.agent.call.AgentCallContext;
import com.taixu.tiangong.common.agent.tool.ToolApprovalDecision;
import com.taixu.tiangong.common.agent.tool.ToolAuditEvent;
import com.taixu.tiangong.common.agent.tool.ToolAuditStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolGovernanceTest {

    private final List<ToolAuditEvent> audits = new ArrayList<>();
    private AgentFunctionDefinition draftTool;
    private AgentFunctionDefinition approvalTool;

    @BeforeEach
    void setUp() throws Exception {
        ToolGovernance.reset();
        ToolGovernance.setAuditSink(audits::add);
        draftTool = tool("draft", Set.of("report:draft"), false);
        approvalTool = tool("publish", Set.of("report:publish"), true);
    }

    @AfterEach
    void tearDown() {
        ToolGovernance.reset();
        AgentCallContext.restore(null);
    }

    @Test
    void scopedToolRequiresCallerContextAndIsAudited() {
        ToolAccessDeniedException denied = assertThrows(ToolAccessDeniedException.class,
                () -> ToolGovernance.invoke(draftTool, Map.of("text", "x"), "draft"));
        assertTrue(denied.getMessage().contains("AgentCallContext"));
        assertEquals(1, audits.size());
        assertEquals(ToolAuditStatus.DENIED, audits.getFirst().status());
    }

    @Test
    void callerContextIsInjectedIntoMethodAndGrantsAccess() {
        AgentCallContext context = new AgentCallContext("u-1", "tenant-1",
                Set.of("report:*"), "trace-1");
        String result = AgentCallContext.callWith(context,
                () -> (String) ToolGovernance.invoke(draftTool, Map.of("text", "hello"), "draft"));
        assertEquals("u-1:hello", result);
        assertEquals(ToolAuditStatus.SUCCESS, audits.getFirst().status());
        assertEquals("u-1", audits.getFirst().userId());
        assertEquals("tenant-1", audits.getFirst().tenantId());
        assertEquals("trace-1", audits.getFirst().traceId());
    }

    @Test
    void tenantRateLimitIsIsolatedAndDeniesBurst() {
        ToolGovernance.setTenantRateLimiter(new TenantRateLimiter(1, 1));
        AgentCallContext tenantA = new AgentCallContext("u-1", "tenant-a",
                Set.of("report:*"), "trace-1");
        AgentCallContext tenantB = new AgentCallContext("u-1", "tenant-b",
                Set.of("report:*"), "trace-2");

        AgentCallContext.callWith(tenantA,
                () -> ToolGovernance.invoke(draftTool, Map.of("text", "one"), "draft"));
        assertThrows(ToolAccessDeniedException.class, () -> AgentCallContext.callWith(tenantA,
                () -> ToolGovernance.invoke(draftTool, Map.of("text", "two"), "draft")));
        // 另一个租户有自己的桶
        AgentCallContext.callWith(tenantB,
                () -> ToolGovernance.invoke(draftTool, Map.of("text", "three"), "draft"));
    }

    @Test
    void missingScopeIsDenied() {
        AgentCallContext context = new AgentCallContext("u-1", "t", Set.of("report:read"), "trace");
        assertThrows(ToolAccessDeniedException.class, () -> AgentCallContext.callWith(context,
                () -> ToolGovernance.invoke(draftTool, Map.of("text", "x"), "draft")));
        assertEquals(ToolAuditStatus.DENIED, audits.getFirst().status());
    }

    @Test
    void approvalRequiredWithoutHandlerFailsClosed() {
        AgentCallContext context = new AgentCallContext("u-1", "t",
                Set.of("report:publish"), "trace");
        ToolAccessDeniedException denied = assertThrows(ToolAccessDeniedException.class,
                () -> AgentCallContext.callWith(context,
                        () -> ToolGovernance.invoke(approvalTool, Map.of("text", "x"), "publish")));
        assertTrue(denied.getMessage().contains("未配置"));
        assertEquals(ToolAuditStatus.APPROVAL_DENIED, audits.getFirst().status());
    }

    @Test
    void approvalHandlerCanApproveOrDeny() {
        AgentCallContext context = new AgentCallContext("u-1", "t",
                Set.of("report:publish"), "trace");
        ToolGovernance.setApprovalHandler(request -> ToolApprovalDecision.APPROVED);
        String approved = AgentCallContext.callWith(context,
                () -> (String) ToolGovernance.invoke(approvalTool, Map.of("text", "x"), "publish"));
        assertEquals("u-1:x", approved);

        ToolGovernance.setApprovalHandler(request -> ToolApprovalDecision.DENIED);
        assertThrows(ToolAccessDeniedException.class, () -> AgentCallContext.callWith(context,
                () -> ToolGovernance.invoke(approvalTool, Map.of("text", "x"), "publish")));
        assertEquals(ToolAuditStatus.APPROVAL_DENIED, audits.getLast().status());
    }

    @Test
    void deploymentDenyListWins() {
        AgentCallContext context = new AgentCallContext("u-1", "t", Set.of("report:*"), "trace");
        ToolGovernance.setToolScope(ToolScope.denyOnly("draft"));
        assertThrows(ToolAccessDeniedException.class, () -> AgentCallContext.callWith(context,
                () -> ToolGovernance.invoke(draftTool, Map.of("text", "x"), "draft")));
        assertFalse(ToolAccessPolicy.isVisible(draftTool, context));
    }

    @Test
    void toolWithoutScopesStaysBackwardCompatible() throws Exception {
        AgentFunctionDefinition plain = tool("plain", Set.of(), false);
        String result = (String) ToolGovernance.invoke(plain, Map.of("text", "x"), "plain");
        assertEquals("noctx:x", result);
        assertTrue(ToolAccessPolicy.isVisible(plain, null));
    }

    private AgentFunctionDefinition tool(String name, Set<String> scopes, boolean requiresApproval)
            throws Exception {
        Method method = ReportTools.class.getDeclaredMethod("call", String.class, AgentCallContext.class);
        List<AgentFunctionDefinition.ParamDefinition> params = List.of(
                new AgentFunctionDefinition.ParamDefinition("text", "string", "", true, ""));
        return new AgentFunctionDefinition(name, "reportTools", name, new ReportTools(), method,
                params, scopes, requiresApproval);
    }

    static class ReportTools {
        String call(String text, AgentCallContext context) {
            return (context == null ? "noctx" : context.userId()) + ":" + text;
        }
    }
}
