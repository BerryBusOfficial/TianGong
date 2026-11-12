package com.taixu.tiangong.core.llm.routing;

import java.time.Instant;

/**
 * 一条成本账：谁、哪次运行、哪个节点、用的哪个模型、多少 token、多少钱。<br>
 * 账本实现应保证可聚合（按 run/user/tenant/node），否则「每节点成本」仍然要回去翻日志。
 */
public record CostEntry(
        String runId,
        String node,
        String userId,
        String tenantId,
        String model,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        long costMicros,
        String currency,
        Instant timestamp
) {
}
