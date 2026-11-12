package com.taixu.tiangong.core.scheduled;

import lombok.extern.slf4j.Slf4j;
import com.taixu.tiangong.common.thread.VirtualThreads;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 定时任务调度引擎：根据 {@link ScheduledTaskRegistry} 中的任务定义启动调度。<br>
 * <br>
 * 调度模型：
 * <ul>
 *     <li>FIXED_RATE：{@code scheduleAtFixedRate}，上次开始 → 下次开始</li>
 *     <li>FIXED_DELAY：{@code scheduleWithFixedDelay}，上次结束 → 下次开始</li>
 *     <li>CRON：虚拟线程循环计算 next 时间，{@code schedule} 到下次执行</li>
 * </ul>
 * <br>
 * 使用虚拟线程执行任务，避免任务阻塞影响调度线程。
 */
@Slf4j
public class ScheduledTaskScheduler implements AutoCloseable {

    private final ScheduledExecutorService scheduler;
    private final ExecutorService worker;
    private final Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

    public ScheduledTaskScheduler() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tiangong-scheduled-scheduler");
            t.setDaemon(true);
            return t;
        });
        this.worker = VirtualThreads.newPerTaskExecutor();
    }

    /**
     * 启动所有定时任务。
     */
    public void start(ScheduledTaskRegistry registry) {
        List<ScheduledTaskDefinition> tasks = registry.tasks();
        if (tasks.isEmpty()) {
            log.info("无定时任务需要调度");
            return;
        }
        for (ScheduledTaskDefinition task : tasks) {
            schedule(task);
        }
        log.info("定时任务调度已启动，共 {} 个任务", tasks.size());
    }

    private void schedule(ScheduledTaskDefinition task) {
        switch (task.mode()) {
            case FIXED_RATE -> scheduleFixedRate(task);
            case FIXED_DELAY -> scheduleFixedDelay(task);
            case CRON -> scheduleCron(task);
            case NONE -> log.warn("任务 {} 无有效调度模式，跳过", task.name());
        }
    }

    private void scheduleFixedRate(ScheduledTaskDefinition task) {
        long initialDelay = task.initialDelay() >= 0 ? task.initialDelay() : 0;
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> executeTask(task),
                initialDelay,
                task.interval(),
                TimeUnit.MILLISECONDS
        );
        futures.put(task.name(), future);
        log.debug("FIXED_RATE 任务已调度: {} interval={}ms", task.name(), task.interval());
    }

    private void scheduleFixedDelay(ScheduledTaskDefinition task) {
        long initialDelay = task.initialDelay() >= 0 ? task.initialDelay() : 0;
        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                () -> executeTask(task),
                initialDelay,
                task.interval(),
                TimeUnit.MILLISECONDS
        );
        futures.put(task.name(), future);
        log.debug("FIXED_DELAY 任务已调度: {} interval={}ms", task.name(), task.interval());
    }

    private void scheduleCron(ScheduledTaskDefinition task) {
        CronExpression cron;
        try {
            cron = new CronExpression(task.cron());
        } catch (IllegalArgumentException e) {
            log.error("Cron 表达式解析失败: {} -> {}", task.name(), task.cron(), e);
            return;
        }
        ZoneId zoneId = task.zone() == null || task.zone().isEmpty()
                ? ZoneId.systemDefault() : ZoneId.of(task.zone());

        // 递归调度：计算 next → schedule → 执行 → 再计算 next
        scheduleCronNext(task, cron, zoneId);
        log.debug("CRON 任务已调度: {} cron={} zone={}", task.name(), task.cron(), zoneId);
    }

    private void scheduleCronNext(ScheduledTaskDefinition task, CronExpression cron, ZoneId zoneId) {
        var now = java.time.ZonedDateTime.now(zoneId);
        var next = cron.next(now);
        long delay = java.time.Duration.between(now, next).toMillis();
        if (delay < 0) {
            delay = 0;
        }

        ScheduledFuture<?> future = scheduler.schedule(() -> {
            executeTask(task);
            // 执行完后调度下一次
            scheduleCronNext(task, cron, zoneId);
        }, delay, TimeUnit.MILLISECONDS);
        futures.put(task.name(), future);
    }

    /**
     * 执行单个任务（虚拟线程，异常不传播）。
     */
    private void executeTask(ScheduledTaskDefinition task) {
        worker.submit(() -> {
            try {
                Method method = task.method();
                method.invoke(task.target());
            } catch (InvocationTargetException e) {
                log.error("定时任务执行失败: {}", task.name(), e.getCause());
            } catch (Exception e) {
                log.error("定时任务调用异常: {}", task.name(), e);
            }
        });
    }

    @Override
    public void close() {
        for (ScheduledFuture<?> future : futures.values()) {
            future.cancel(false);
        }
        futures.clear();
        scheduler.shutdownNow();
        worker.shutdownNow();
        log.info("定时任务调度已关闭");
    }
}
