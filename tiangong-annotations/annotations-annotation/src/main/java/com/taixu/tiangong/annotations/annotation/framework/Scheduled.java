package com.taixu.tiangong.annotations.annotation.framework;

import com.taixu.tiangong.annotations.annotation.framework.meta.Framework;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

/**
 * 定时任务
 */
@Framework
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Scheduled {

    /**
     * 使用 Cron 表达式定义执行规则
     * @return Cron表达式，仅支持6个字段
     */
    String cron() default "";

    /**
     * Cron 表达式解析所使用的时区
     * @return 时区
     */
    String zone() default "";

    /**
     * 上一次任务执行结束到下一次任务开始之间的间隔（毫秒）
     * @return 间隔
     */
    long fixedDelay() default -1;

    /**
     * 上一次任务开始到下一次任务开始之间的固定间隔（毫秒）
     * @return 间隔
     */
    long fixedRate() default -1;

    /**
     * 首次执行的延迟时间（毫秒），仅对 fixedDelay / fixedRate 生效
     * @return 延迟时间
     */
    long initialDelay() default -1;

    /**
     * 指定 fixedDelay、fixedRate、initialDelay 的时间单位
     * @return 时间单位
     */
    TimeUnit timeUnit() default TimeUnit.MILLISECONDS;

}
