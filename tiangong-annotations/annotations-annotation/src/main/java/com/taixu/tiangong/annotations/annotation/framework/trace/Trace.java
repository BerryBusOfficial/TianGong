package com.taixu.tiangong.annotations.annotation.framework.trace;

import com.taixu.tiangong.annotations.annotation.framework.meta.Framework;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

@Framework
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
public @interface Trace {

    /**
     * 跟踪标签/分组，TraceHandler 可按 value 过滤
     */
    String value() default "";

    /**
     * 方法执行超时阈值，0 表示不监控超时（仅对方法级 @Trace 生效）。
     * 超过阈值时触发 TraceHandler.onMethodTimeout。
     */
    long timeout() default 0;

    /**
     * 超时阈值的时间单位
     */
    TimeUnit unit() default TimeUnit.MILLISECONDS;
}
