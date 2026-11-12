package com.taixu.tiangong.core.llm.routing;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存成本账本：单机/测试用；进程退出即丢失，不假装持久化。
 */
public final class InMemoryCostLedger implements CostLedger {

    private final List<CostEntry> entries = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<String, AtomicLong> reserved = new ConcurrentHashMap<>();

    @Override
    public void record(CostEntry entry) {
        if (entry != null) {
            entries.add(entry);
        }
    }

    public List<CostEntry> entries() {
        return List.copyOf(entries);
    }

    public long totalTokens() {
        return entries.stream().mapToLong(CostEntry::totalTokens).sum();
    }

    public long totalCostMicros() {
        return entries.stream().mapToLong(CostEntry::costMicros).sum();
    }

    @Override
    public long totalCostMicros(String tenantId) {
        if (tenantId == null) {
            return 0;
        }
        return entries.stream()
                .filter(entry -> tenantId.equals(entry.tenantId()))
                .mapToLong(CostEntry::costMicros)
                .sum();
    }

    /**
     * 原子预留：spent + 当前预留 + 本次预留 ≤ limit 才成功。<br>
     * 对同一租户的预留加锁，避免两个并发请求都读到「还没花完」而同时放行。
     */
    @Override
    public boolean tryReserve(String tenantId, long micros, long limitMicros) {
        if (tenantId == null) {
            return true;
        }
        long amount = Math.max(0, micros);
        AtomicLong counter = reserved.computeIfAbsent(tenantId, key -> new AtomicLong());
        synchronized (counter) {
            long spent = totalCostMicros(tenantId);
            long alreadyReserved = counter.get();
            if (spent + alreadyReserved + amount > limitMicros) {
                return false;
            }
            counter.addAndGet(amount);
            return true;
        }
    }

    @Override
    public void release(String tenantId, long micros) {
        if (tenantId == null) {
            return;
        }
        AtomicLong counter = reserved.get(tenantId);
        if (counter == null) {
            return;
        }
        synchronized (counter) {
            long next = counter.get() - Math.max(0, micros);
            counter.set(Math.max(0, next));
        }
    }

    public long reservedMicros(String tenantId) {
        AtomicLong counter = reserved.get(tenantId);
        return counter == null ? 0 : counter.get();
    }

    public void clear() {
        entries.clear();
    }
}
