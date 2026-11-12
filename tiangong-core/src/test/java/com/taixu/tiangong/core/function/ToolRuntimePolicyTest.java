package com.taixu.tiangong.core.function;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRuntimePolicyTest {

    @Test
    void timeoutFailsToolCall() {
        ToolRuntimePolicy policy = ToolRuntimePolicy.builder()
                .timeout(Duration.ofMillis(30))
                .maxAttempts(1)
                .build();
        assertThrows(ToolRuntimePolicy.ToolTimeoutException.class, () ->
                policy.execute("slow", () -> {
                    Thread.sleep(200);
                    return "ok";
                }));
    }

    @Test
    void retriesUntilSuccess() {
        AtomicInteger attempts = new AtomicInteger();
        ToolRuntimePolicy policy = ToolRuntimePolicy.builder()
                .timeout(Duration.ofSeconds(1))
                .maxAttempts(3)
                .initialBackoff(Duration.ZERO)
                .build();
        Object result = policy.execute("flaky", () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("temporary");
            }
            return "ok";
        });
        assertEquals("ok", result);
        assertEquals(3, attempts.get());
    }

    @Test
    void circuitOpensAndRecoversAfterCooldown() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        ToolRuntimePolicy policy = ToolRuntimePolicy.builder()
                .timeout(Duration.ofSeconds(1))
                .maxAttempts(1)
                .failureThreshold(2)
                .openDuration(Duration.ofMillis(100))
                .build();
        Runnable failing = () -> assertThrows(IllegalStateException.class, () ->
                policy.execute("broken", () -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("boom");
                }));
        failing.run();
        failing.run();
        assertThrows(ToolRuntimePolicy.ToolCircuitOpenException.class, () ->
                policy.execute("broken", () -> "never"));
        Thread.sleep(150);
        assertEquals("recovered", policy.execute("broken", () -> "recovered"));
        assertTrue(attempts.get() >= 2);
    }

    @Test
    void defaultIsSingleAttempt() {
        AtomicInteger attempts = new AtomicInteger();
        ToolRuntimePolicy policy = ToolRuntimePolicy.defaults();
        assertThrows(IllegalStateException.class, () -> policy.execute("once", () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("boom");
        }));
        assertEquals(1, attempts.get());
    }
}
