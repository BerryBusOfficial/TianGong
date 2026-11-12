package com.taixu.tiangong.core.scheduled;

import com.taixu.tiangong.annotations.annotation.framework.Scheduled;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * 定时任务定义：从 {@link Scheduled} 注解解析得到。<br>
 * <br>
 * 三种调度模式（互斥，优先级 cron > fixedDelay > fixedRate）：
 * <ul>
 *     <li>{@link Mode#CRON}：Cron 表达式驱动</li>
 *     <li>{@link Mode#FIXED_DELAY}：上次结束 → 下次开始的间隔</li>
 *     <li>{@link Mode#FIXED_RATE}：上次开始 → 下次开始的间隔</li>
 * </ul>
 *
 * @param name        任务名（Bean 名 + 方法名）
 * @param target      Bean 实例
 * @param method      目标方法
 * @param mode        调度模式
 * @param cron         Cron 表达式（mode=CRON 时有效）
 * @param zone         Cron 时区（mode=CRON 时有效，空串=系统默认）
 * @param interval     间隔毫秒（mode=FIXED_DELAY/FIXED_RATE 时有效）
 * @param initialDelay 初始延迟毫秒（FIXED 模式，-1=0）
 */
public record ScheduledTaskDefinition(
        String name,
        Object target,
        Method method,
        Mode mode,
        String cron,
        String zone,
        long interval,
        long initialDelay
) {

    public enum Mode {
        CRON,
        FIXED_DELAY,
        FIXED_RATE,
        NONE
    }

    /**
     * 从注解和方法解析任务定义。
     */
    public static ScheduledTaskDefinition of(String beanName, Object target, Method method, Scheduled ann) {
        method.setAccessible(true);
        Mode mode;
        String cron = "";
        String zone = "";
        long interval = -1;
        long initialDelay = -1;

        if (!ann.cron().isEmpty()) {
            mode = Mode.CRON;
            cron = ann.cron();
            zone = ann.zone();
        } else if (ann.fixedDelay() >= 0) {
            mode = Mode.FIXED_DELAY;
            interval = toMillis(ann.fixedDelay(), ann.timeUnit());
            initialDelay = ann.initialDelay() >= 0
                    ? toMillis(ann.initialDelay(), ann.timeUnit()) : 0;
        } else if (ann.fixedRate() >= 0) {
            mode = Mode.FIXED_RATE;
            interval = toMillis(ann.fixedRate(), ann.timeUnit());
            initialDelay = ann.initialDelay() >= 0
                    ? toMillis(ann.initialDelay(), ann.timeUnit()) : 0;
        } else {
            mode = Mode.NONE;
        }

        return new ScheduledTaskDefinition(
                beanName + "#" + method.getName(),
                target,
                method,
                mode,
                cron,
                zone,
                interval,
                initialDelay
        );
    }

    private static long toMillis(long value, TimeUnit unit) {
        return unit.toMillis(value);
    }

    @Override
    public String toString() {
        return "ScheduledTask[" + name + ", mode=" + mode +
                (mode == Mode.CRON ? ", cron=" + cron : ", interval=" + interval + "ms") + "]";
    }
}
