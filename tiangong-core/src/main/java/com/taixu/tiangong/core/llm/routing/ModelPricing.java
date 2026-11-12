package com.taixu.tiangong.core.llm.routing;

/**
 * 模型计价：以「微美元 / 百万 token」为单位，避免 double 累加误差。
 * 例如 $0.27 / 1M prompt tokens = 270_000 micros。
 */
public record ModelPricing(
        long promptMicrosPerMillion,
        long completionMicrosPerMillion,
        String currency
) {

    public ModelPricing {
        if (promptMicrosPerMillion < 0 || completionMicrosPerMillion < 0) {
            throw new IllegalArgumentException("计价不能为负数");
        }
        currency = currency == null || currency.isBlank() ? "USD" : currency;
    }

    public static ModelPricing free() {
        return new ModelPricing(0, 0, "USD");
    }

    public long costMicros(long promptTokens, long completionTokens) {
        long prompt = Math.max(0, promptTokens) * promptMicrosPerMillion;
        long completion = Math.max(0, completionTokens) * completionMicrosPerMillion;
        return (prompt + completion) / 1_000_000L;
    }
}
