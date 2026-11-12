package com.taixu.tiangong.common.agent.call;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCallContextTest {

    @Test
    void wildcardScopeMatchesPrefix() {
        AgentCallContext context = new AgentCallContext("u1", "t1", Set.of("report:*"), "trace");
        assertTrue(context.hasScope("report:draft"));
        assertTrue(context.hasScope("report:*"));
        assertFalse(context.hasScope("admin:delete"));
        assertTrue(context.hasAllScopes(Set.of("report:draft", "report:*")));
        assertFalse(context.hasAllScopes(Set.of("report:draft", "admin:delete")));
    }

    @Test
    void declaredWildcardMatchesConcreteGrant() {
        AgentCallContext context = new AgentCallContext("u1", "t1", Set.of("report:draft"), "trace");
        assertTrue(context.hasScope("report:*"));
    }

    @Test
    void fromHeadersIsCaseInsensitive() {
        AgentCallContext context = AgentCallContext.fromHeaders(Map.of(
                "x-user-id", "u-1",
                "X-TENANT-ID", "t-1",
                "X-Scopes", "report:draft, admin:read",
                "traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"));
        assertEquals("u-1", context.userId());
        assertEquals("t-1", context.tenantId());
        assertEquals(2, context.scopes().size());
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", context.traceId());
    }

    @Test
    void noIdentityHeadersMeansNoContext() {
        assertNull(AgentCallContext.fromHeaders(Map.of("Content-Type", "application/json")));
    }

    @Test
    void bindAndRestoreDoNotLeakAcrossRequests() {
        AgentCallContext first = new AgentCallContext("u1", "t1", Set.of(), "a");
        AgentCallContext second = new AgentCallContext("u2", "t2", Set.of(), "b");
        AgentCallContext previous = AgentCallContext.bind(first);
        try {
            assertEquals("u1", AgentCallContext.current().userId());
            AgentCallContext.runWith(second, () -> assertEquals("u2", AgentCallContext.current().userId()));
            assertEquals("u1", AgentCallContext.current().userId());
        } finally {
            AgentCallContext.restore(previous);
        }
        assertNull(AgentCallContext.current());
    }
}
