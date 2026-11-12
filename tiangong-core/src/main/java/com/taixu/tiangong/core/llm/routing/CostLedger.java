package com.taixu.tiangong.core.llm.routing;

/**
 * 成本账本出口。默认内存实现用于单机/测试；生产可接 DB 或计量系统。
 */
@FunctionalInterface
public interface CostLedger {

    void record(CostEntry entry);

    /** 某租户累计成本；没有账本能力的实现返回 0（预算守护会因此放行） */
    default long totalCostMicros(String tenantId) {
        return 0;
    }

    CostLedger NOOP = new CostLedger() {
        @Override
        public void record(CostEntry entry) {
        }
    };
}
