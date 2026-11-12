package com.taixu.tiangong.core.llm.routing;

import com.taixu.tiangong.common.agent.call.AgentCallContext;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 租户预算：读取成本账本里该租户已花费，超过上限则在调用前拒绝。<br>
 * <br>
 * 这是「软预算」：并发请求可能同时通过检查，超额部分由账本事后可见；强一致预算需要
 * 账本侧原子扣减（DB/Redis），不在本类假装实现。
 */
public final class TenantBudgetGuard implements BudgetGuard {

    private final CostLedger ledger;
    private final Map<String, Long> limitsMicros;

    private TenantBudgetGuard(CostLedger ledger, Map<String, Long> limitsMicros) {
        this.ledger = ledger == null ? CostLedger.NOOP : ledger;
        this.limitsMicros = Map.copyOf(limitsMicros);
    }

    public static Builder builder(CostLedger ledger) {
        return new Builder(ledger);
    }

    @Override
    public void check(AgentCallContext context) {
        if (context == null || context.tenantId() == null || context.tenantId().isBlank()) {
            return;
        }
        Long limit = limitsMicros.get(context.tenantId());
        if (limit == null) {
            return;   // 未配置预算的租户不限制
        }
        long spent = ledger.totalCostMicros(context.tenantId());
        if (spent >= limit) {
            throw new BudgetExceededException(
                    "租户 [" + context.tenantId() + "] 预算已用尽: spent=" + spent
                            + " micros, limit=" + limit + " micros");
        }
    }

    @Override
    public Reservation reserve(AgentCallContext context, long estimatedMicros) {
        if (context == null || context.tenantId() == null || context.tenantId().isBlank()) {
            return Reservation.NOOP;
        }
        String tenantId = context.tenantId();
        Long limit = limitsMicros.get(tenantId);
        if (limit == null) {
            return Reservation.NOOP;
        }
        check(context);
        long estimate = Math.max(0, estimatedMicros);
        if (!ledger.tryReserve(tenantId, estimate, limit)) {
            throw new BudgetExceededException(
                    "租户 [" + tenantId + "] 预算预留失败: estimated=" + estimate
                            + " micros, limit=" + limit + " micros");
        }
        AtomicBoolean settled = new AtomicBoolean();
        return actualMicros -> {
            if (settled.compareAndSet(false, true)) {
                // 实际成本由 ModelRouteExecutor 记录；这里只释放预留，避免重复计费
                ledger.release(tenantId, estimate);
            }
        };
    }

    public static final class Builder {

        private final CostLedger ledger;
        private final java.util.Map<String, Long> limits = new java.util.LinkedHashMap<>();

        private Builder(CostLedger ledger) {
            this.ledger = ledger;
        }

        public Builder limit(String tenantId, long micros) {
            limits.put(tenantId, Math.max(0, micros));
            return this;
        }

        public TenantBudgetGuard build() {
            return new TenantBudgetGuard(ledger, limits);
        }
    }
}
