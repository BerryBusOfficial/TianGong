package com.taixu.tiangong.trace;

import java.time.Instant;

/**
 * 一次模型调用的 trace。
 *
 * @param promptHash     完整 prompt 的 SHA-256（用于精确比对，不泄露原文）
 * @param promptPreview  脱敏/截断后的 prompt 摘要
 * @param resultPreview  脱敏/截断后的结果摘要
 * @param costMicros     本次调用的微美元成本（无计价时为 0）
 * @param status         {@code success} / {@code empty} / {@code error}
 */
public record LlmTraceRecord(
        String runId,
        String turnId,
        String node,
        String model,
        String promptHash,
        String promptPreview,
        String resultPreview,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        long costMicros,
        String currency,
        long durationMs,
        String status,
        String error,
        Instant timestamp
) implements TraceEvent {

    public LlmTraceRecord {
        promptTokens = Math.max(0, promptTokens);
        completionTokens = Math.max(0, completionTokens);
        totalTokens = Math.max(0, totalTokens);
        costMicros = Math.max(0, costMicros);
        durationMs = Math.max(0, durationMs);
        currency = currency == null || currency.isBlank() ? "USD" : currency;
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }

    public boolean success() {
        return "success".equals(status);
    }
}
