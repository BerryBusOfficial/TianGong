package com.taixu.tiangong.core.function;

import com.taixu.tiangong.common.agent.call.AgentCallContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantToolPolicyTest {

    @AfterEach
    void tearDown() {
        ToolGovernance.reset();
    }

    @Test
    void tenantScopeOverridesGlobalScope() {
        ToolGovernance.setTenantToolPolicy(TenantToolPolicy.builder()
                .tenant("tenant-a", ToolScope.allow("report:*"))
                .tenant("tenant-b", ToolScope.allow("chat:*"))
                .defaultScope(ToolScope.allow("common:*"))
                .build());

        AgentFunctionDefinition reportTool = new AgentFunctionDefinition(
                "draft", "report", "", null, null, List.of());

        AgentCallContext tenantA = new AgentCallContext("u", "tenant-a", Set.of(), "trace");
        AgentCallContext tenantB = new AgentCallContext("u", "tenant-b", Set.of(), "trace");
        AgentCallContext tenantC = new AgentCallContext("u", "tenant-c", Set.of(), "trace");

        assertTrue(ToolAccessPolicy.isVisible(reportTool, tenantA));
        assertFalse(ToolAccessPolicy.isVisible(reportTool, tenantB));
        assertFalse(ToolAccessPolicy.isVisible(reportTool, tenantC));
    }

    @Test
    void tenantWithoutDefaultFallsBackToGlobalScope() {
        ToolGovernance.setToolScope(ToolScope.denyOnly("report.draft"));
        ToolGovernance.setTenantToolPolicy(TenantToolPolicy.builder()
                .tenant("tenant-a", ToolScope.allow("report:*"))
                .build());
        AgentFunctionDefinition reportTool = new AgentFunctionDefinition(
                "draft", "report", "", null, null, List.of());

        assertTrue(ToolAccessPolicy.isVisible(reportTool,
                new AgentCallContext("u", "tenant-a", Set.of(), "trace")));
        assertFalse(ToolAccessPolicy.isVisible(reportTool,
                new AgentCallContext("u", "tenant-b", Set.of(), "trace")));
    }

    @Test
    void noTenantPolicyFallsBackToGlobalScope() {
        ToolGovernance.setToolScope(ToolScope.denyOnly("report.draft"));
        AgentFunctionDefinition reportTool = new AgentFunctionDefinition(
                "draft", "report", "", null, null, List.of());
        AgentCallContext tenantA = new AgentCallContext("u", "tenant-a", Set.of(), "trace");
        assertFalse(ToolAccessPolicy.isVisible(reportTool, tenantA));
    }

    @Test
    void tenantPolicyStillRequiresToolScopes() {
        ToolGovernance.setTenantToolPolicy(TenantToolPolicy.builder()
                .tenant("tenant-a", ToolScope.unrestricted())
                .build());
        AgentFunctionDefinition scopedTool = new AgentFunctionDefinition(
                "publish", "report", "",
                null, null, List.of(), Set.of("report:publish"), false);

        AgentCallContext noScope = new AgentCallContext("u", "tenant-a", Set.of(), "trace");
        AgentCallContext withScope = new AgentCallContext(
                "u", "tenant-a", Set.of("report:publish"), "trace");

        assertFalse(ToolAccessPolicy.isVisible(scopedTool, noScope));
        assertTrue(ToolAccessPolicy.isVisible(scopedTool, withScope));
    }
}
