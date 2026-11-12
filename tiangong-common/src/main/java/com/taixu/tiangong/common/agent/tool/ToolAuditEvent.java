package com.taixu.tiangong.common.agent.tool;

import java.time.Instant;

/**
 * 一次工具调用的审计记录。<br>
 * <br>
 * 包含「谁、在哪个租户、用什么 trace、调了什么、结果如何、耗时多少」。参数与结果
 * 以 JSON 文本保存，<b>是否脱敏由审计实现决定</b> —— 框架不假设所有参数都能安全落盘，
 * 也不假装自己会脱敏。
 *
 * @param argumentsJson 参数 JSON（序列化失败时为 {@code String.valueOf(args)}）
 * @param detail        失败原因/审批说明；成功时可为 null
 */
public record ToolAuditEvent(
        String toolName,
        String userId,
        String tenantId,
        String traceId,
        String argumentsJson,
        ToolAuditStatus status,
        long durationMs,
        String detail,
        Instant timestamp
) {
}
