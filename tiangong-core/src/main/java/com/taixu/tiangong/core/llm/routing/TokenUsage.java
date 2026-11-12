package com.taixu.tiangong.core.llm.routing;

/**
 * token 用量。所有 provider 的 usage 统一成这个形状，账本不关心厂商。
 */
public record TokenUsage(long promptTokens, long completionTokens, long totalTokens) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0);

    public TokenUsage {
        promptTokens = Math.max(0, promptTokens);
        completionTokens = Math.max(0, completionTokens);
        totalTokens = totalTokens > 0 ? totalTokens : promptTokens + completionTokens;
    }
}
