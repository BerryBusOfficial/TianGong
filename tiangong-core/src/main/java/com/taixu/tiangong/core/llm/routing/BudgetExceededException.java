package com.taixu.tiangong.core.llm.routing;

/**
 * 预算超限：在一次模型调用开始前就拒绝，而不是等花完再报错。<br>
 * 异常而不是静默降级：预算策略是业务决定（停/降级/告警），框架不替业务选。
 */
public class BudgetExceededException extends RuntimeException {

    public BudgetExceededException(String message) {
        super(message);
    }
}
