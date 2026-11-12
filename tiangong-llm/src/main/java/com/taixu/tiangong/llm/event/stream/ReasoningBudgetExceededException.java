package com.taixu.tiangong.llm.event.stream;

/**
 * 推理 token 超预算。流已被取消，调用方应决定降级/重试/结束，而不是继续等正文。
 */
public class ReasoningBudgetExceededException extends RuntimeException {

    private final int maxReasoningTokens;
    private final long observedTokens;

    public ReasoningBudgetExceededException(int maxReasoningTokens, long observedTokens) {
        super("推理 token 超预算: observed≈" + observedTokens + ", max=" + maxReasoningTokens);
        this.maxReasoningTokens = maxReasoningTokens;
        this.observedTokens = observedTokens;
    }

    public int maxReasoningTokens() {
        return maxReasoningTokens;
    }

    public long observedTokens() {
        return observedTokens;
    }
}
