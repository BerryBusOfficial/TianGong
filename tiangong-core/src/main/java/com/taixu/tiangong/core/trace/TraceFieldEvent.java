package com.taixu.tiangong.core.trace;

/**
 * 字段状态变更事件（方法边界快照对比产生）。<br>
 * 注意：事件携带目标实例引用，handler 不应长期持有事件对象。
 */
public record TraceFieldEvent(
        String fieldName,
        Class<?> targetClass,
        Object target,
        Object oldValue,
        Object newValue,
        long timestamp
) {
}
