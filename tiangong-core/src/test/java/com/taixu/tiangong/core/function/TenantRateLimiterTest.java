package com.taixu.tiangong.core.function;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantRateLimiterTest {

    @Test
    void bucketIsIsolatedPerTenant() {
        TenantRateLimiter limiter = new TenantRateLimiter(0.001, 1);
        assertTrue(limiter.tryAcquire("tenant-a"));
        assertFalse(limiter.tryAcquire("tenant-a"));
        assertTrue(limiter.tryAcquire("tenant-b"));
    }

    @Test
    void refillsOverTime() throws Exception {
        TenantRateLimiter limiter = new TenantRateLimiter(1_000, 1);
        assertTrue(limiter.tryAcquire("tenant-a"));
        assertFalse(limiter.tryAcquire("tenant-a"));
        Thread.sleep(5);
        assertTrue(limiter.tryAcquire("tenant-a"));
    }
}
