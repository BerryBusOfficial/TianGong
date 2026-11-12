package com.taixu.tiangong.core.function;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 租户级工具调用限流（令牌桶）。<br>
 * <br>
 * 与 P1 的成本预算互补：预算管“花多少钱”，这里管“打多快”。只在单进程内一致；
 * 多实例部署需要 Redis/网关级令牌桶。
 */
public final class TenantRateLimiter {

    private final double permitsPerSecond;
    private final int burst;
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TenantRateLimiter(double permitsPerSecond, int burst) {
        if (permitsPerSecond <= 0) {
            throw new IllegalArgumentException("permitsPerSecond 必须为正数");
        }
        if (burst <= 0) {
            throw new IllegalArgumentException("burst 必须为正数");
        }
        this.permitsPerSecond = permitsPerSecond;
        this.burst = burst;
    }

    public boolean tryAcquire(String tenantId) {
        return tryAcquire(tenantId, 1);
    }

    public boolean tryAcquire(String tenantId, int permits) {
        if (tenantId == null || tenantId.isBlank() || permits <= 0) {
            return true;
        }
        Bucket bucket = buckets.computeIfAbsent(tenantId,
                ignored -> new Bucket(burst, System.nanoTime()));
        return bucket.tryAcquire(permits, permitsPerSecond, burst);
    }

    private static final class Bucket {

        private double tokens;
        private long lastNanos;

        private Bucket(int burst, long now) {
            this.tokens = burst;
            this.lastNanos = now;
        }

        private synchronized boolean tryAcquire(int permits, double rate, int burst) {
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastNanos) / 1_000_000_000.0;
            lastNanos = now;
            tokens = Math.min(burst, tokens + elapsedSeconds * rate);
            if (tokens >= permits) {
                tokens -= permits;
                return true;
            }
            return false;
        }
    }
}
