package com.taixu.tiangong.core.function;

import com.taixu.tiangong.common.agent.call.AgentCallContext;
import com.taixu.tiangong.common.agent.tool.ToolApprovalDecision;
import com.taixu.tiangong.common.agent.tool.ToolApprovalRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HumanApprovalServiceTest {

    private static final AgentCallContext CONTEXT = new AgentCallContext(
            "u-1", "tenant-1", Set.of("report:publish"), "trace-1");

    @Test
    void approveCompletesPendingDecision() throws Exception {
        try (HumanApprovalService service = new HumanApprovalService(Duration.ofSeconds(2))) {
            CompletableFuture<ToolApprovalDecision> decision = CompletableFuture.supplyAsync(
                    () -> service.decide(request()));

            HumanApprovalService.PendingApproval pending = awaitPending(service);
            assertTrue(service.approve(pending.id(), "alice"));
            assertEquals(ToolApprovalDecision.APPROVED, decision.get(1, TimeUnit.SECONDS));
            assertTrue(service.pending().isEmpty());
            assertFalse(service.approve(pending.id(), "bob"), "同一 ticket 不能重复决定");
        }
    }

    @Test
    void rejectCompletesPendingDecision() throws Exception {
        try (HumanApprovalService service = new HumanApprovalService(Duration.ofSeconds(2))) {
            CompletableFuture<ToolApprovalDecision> decision = CompletableFuture.supplyAsync(
                    () -> service.decide(request()));
            HumanApprovalService.PendingApproval pending = awaitPending(service);
            assertTrue(service.reject(pending.id(), "alice"));
            assertEquals(ToolApprovalDecision.DENIED, decision.get(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void timeoutDeniesAndClearsTicket() {
        try (HumanApprovalService service = new HumanApprovalService(Duration.ofMillis(50))) {
            assertEquals(ToolApprovalDecision.DENIED, service.decide(request()));
            assertTrue(service.pending().isEmpty());
        }
    }

    @Test
    void closeDeniesWaitingApprovals() throws Exception {
        HumanApprovalService service = new HumanApprovalService(Duration.ofSeconds(5));
        CompletableFuture<ToolApprovalDecision> decision = CompletableFuture.supplyAsync(
                () -> service.decide(request()));
        awaitPending(service);
        service.close();
        assertEquals(ToolApprovalDecision.DENIED, decision.get(1, TimeUnit.SECONDS));
    }

    @Test
    void pendingPreviewRedactsSecrets() throws Exception {
        try (HumanApprovalService service = new HumanApprovalService(Duration.ofSeconds(2))) {
            CompletableFuture<ToolApprovalDecision> ignored = CompletableFuture.supplyAsync(
                    () -> service.decide(new ToolApprovalRequest("publish",
                            Map.of("token", "sk-supersecret12345"), CONTEXT)));
            HumanApprovalService.PendingApproval pending = awaitPending(service);
            assertFalse(pending.argumentsPreview().contains("supersecret"));
            assertTrue(pending.argumentsPreview().contains("sk-***"));
            service.reject(pending.id(), "alice");
            ignored.get(1, TimeUnit.SECONDS);
        }
    }

    private static ToolApprovalRequest request() {
        return new ToolApprovalRequest("publish_release", Map.of("version", "1.0.0"), CONTEXT);
    }

    private static HumanApprovalService.PendingApproval awaitPending(HumanApprovalService service)
            throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            List<HumanApprovalService.PendingApproval> pending = service.pending();
            if (!pending.isEmpty()) {
                return pending.getFirst();
            }
            Thread.sleep(5);
        }
        throw new AssertionError("审批 ticket 未出现");
    }
}
