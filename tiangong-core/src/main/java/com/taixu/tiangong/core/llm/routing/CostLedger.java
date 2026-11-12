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

    /**
     * 原子预留预算（用于并发场景的强一致检查）。<br>
     * 默认实现不预留、直接放行；能保证并发的账本应覆盖本方法。
     *
     * @return true = 预留成功；false = 超预算
     */
    default boolean tryReserve(String tenantId, long micros, long limitMicros) {
        return true;
    }

    /** 释放预留额度（调用结束、无论成功失败都要释放） */
    default void release(String tenantId, long micros) {
    }

    CostLedger NOOP = new CostLedger() {
        @Override
        public void record(CostEntry entry) {
        }
    };
}
