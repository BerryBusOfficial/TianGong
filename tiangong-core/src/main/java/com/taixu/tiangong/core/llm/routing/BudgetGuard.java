package com.taixu.tiangong.core.llm.routing;

import com.taixu.tiangong.common.agent.call.AgentCallContext;

/**
 * 调用前预算检查。实现方可以按租户、用户或运行维度判断；无上下文时默认放行。
 */
@FunctionalInterface
public interface BudgetGuard {

    void check(AgentCallContext context);

    /**
     * 预留预算：并发场景下应原子执行，成功后返回的 {@link Reservation} 必须在调用结束时
     * settle（无论成功失败），否则预留额度会泄漏，最终把租户永久卡死。
     */
    default Reservation reserve(AgentCallContext context, long estimatedMicros) {
        check(context);
        return Reservation.NOOP;
    }

    @FunctionalInterface
    interface Reservation {
        void settle(long actualMicros);

        Reservation NOOP = actualMicros -> {
        };
    }

    BudgetGuard NOOP = context -> {
    };
}
