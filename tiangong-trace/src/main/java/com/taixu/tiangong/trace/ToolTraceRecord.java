package com.taixu.tiangong.trace;

import java.time.Instant;

/**
 * 一次工具调用的 trace：参数与结果只留脱敏摘要，足够定位「模型传了什么、工具回了什么」，
 * 不把全量业务数据落进观测库。
 */
public record ToolTraceRecord(
        String runId,
        String turnId,
        String node,
        String toolName,
        String callId,
        String argumentsPreview,
        String resultPreview,
        String status,
        long durationMs,
        String error,
        Instant timestamp
) implements TraceEvent {

    public ToolTraceRecord {
        durationMs = Math.max(0, durationMs);
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }
}
