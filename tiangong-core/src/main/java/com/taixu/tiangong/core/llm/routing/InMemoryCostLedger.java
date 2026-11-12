package com.taixu.tiangong.core.llm.routing;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存成本账本：单机/测试用；进程退出即丢失，不假装持久化。
 */
public final class InMemoryCostLedger implements CostLedger {

    private final List<CostEntry> entries = new CopyOnWriteArrayList<>();

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

    public void clear() {
        entries.clear();
    }
}
