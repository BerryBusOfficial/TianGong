package com.taixu.tiangong.core.trace;

import java.util.concurrent.TimeUnit;

/**
 * 方法计时事件：TraceHandler.onMethodTimed / onMethodTimeout 的上下文。
 */
public record TimerEvent(
        String methodName,          // "MyService.slowMethod"
        Class<?> declaringClass,    // 方法所属类
        long elapsedNanos,          // 实际耗时（纳秒）
        long timeoutNanos,          // 阈值（纳秒），未配置超时时为 0
        long timeout,               // @Trace 上的 timeout 原始值，未配置时为 0
        TimeUnit unit               // @Trace 上的 unit 原始值
) {
    /** 实际耗时毫秒数 */
    public long elapsedMillis() {
        return elapsedNanos / 1_000_000;
    }
}
