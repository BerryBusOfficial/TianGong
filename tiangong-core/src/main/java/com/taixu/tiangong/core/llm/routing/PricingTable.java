package com.taixu.tiangong.core.llm.routing;

import java.util.Map;

/**
 * 模型价格表：模型名 → 单价，未配置的模型走默认价（默认 0）。<br>
 * 价格是部署事实，不硬编码在路由里；同一套代码在测试/生产用不同价格表即可。
 */
public final class PricingTable {

    private final Map<String, ModelPricing> prices;
    private final ModelPricing defaultPricing;

    private PricingTable(Map<String, ModelPricing> prices, ModelPricing defaultPricing) {
        this.prices = Map.copyOf(prices);
        this.defaultPricing = defaultPricing == null ? ModelPricing.free() : defaultPricing;
    }

    public static Builder builder() {
        return new Builder();
    }

    public ModelPricing priceFor(String model) {
        return prices.getOrDefault(model, defaultPricing);
    }

    public static final class Builder {

        private final java.util.Map<String, ModelPricing> prices = new java.util.LinkedHashMap<>();
        private ModelPricing defaultPricing = ModelPricing.free();

        public Builder price(String model, ModelPricing pricing) {
            prices.put(model, pricing);
            return this;
        }

        public Builder defaultPricing(ModelPricing pricing) {
            this.defaultPricing = pricing;
            return this;
        }

        public PricingTable build() {
            return new PricingTable(prices, defaultPricing);
        }
    }
}
