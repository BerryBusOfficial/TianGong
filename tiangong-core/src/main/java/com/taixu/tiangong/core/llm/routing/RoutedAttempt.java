package com.taixu.tiangong.core.llm.routing;

/**
 * 一次候选模型尝试的记录。
 *
 * @param status {@code success} / {@code empty} / {@code error}
 * @param detail 空回答/异常时的摘要
 */
public record RoutedAttempt(
        String model,
        Status status,
        long durationMs,
        TokenUsage usage,
        String detail
) {

    public enum Status {
        SUCCESS,
        EMPTY,
        ERROR
    }

    public static RoutedAttempt success(String model, long durationMs, TokenUsage usage) {
        return new RoutedAttempt(model, Status.SUCCESS, durationMs, usage, null);
    }

    public static RoutedAttempt empty(String model, long durationMs) {
        return new RoutedAttempt(model, Status.EMPTY, durationMs, TokenUsage.ZERO, "空回答");
    }

    public static RoutedAttempt error(String model, long durationMs, String detail) {
        return new RoutedAttempt(model, Status.ERROR, durationMs, TokenUsage.ZERO, detail);
    }
}
