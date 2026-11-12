package com.taixu.tiangong.core.llm.routing;

import com.taixu.tiangong.common.agent.call.AgentCallContext;

/**
 * 调用前预算检查。实现方可以按租户、用户或运行维度判断；无上下文时默认放行。
 */
@FunctionalInterface
public interface BudgetGuard {

    void check(AgentCallContext context);

    BudgetGuard NOOP = context -> {
    };
}
