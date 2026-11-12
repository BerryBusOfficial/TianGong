    package com.taixu.tiangong.core.trace;

import java.util.Arrays;

/**
 * 方法跟踪事件。<br>
 * 注意：事件携带目标实例引用，handler 不应长期持有事件对象。
 */
public record TraceMethodEvent(
        String targetName,       // 类名.方法名
        Class<?> targetClass,
        Object target,           // this 实例
        Object[] args,
        Object result,           // 正常返回时
        long startNanos,
        long elapsedNanos        // 出口/异常时耗时
) {

    @Override
    public String toString() {
        return "TraceMethodEvent[" + targetName
                + ", args=" + Arrays.toString(args)
                + ", result=" + result
                + ", elapsedNanos=" + elapsedNanos + "]";
    }
}
