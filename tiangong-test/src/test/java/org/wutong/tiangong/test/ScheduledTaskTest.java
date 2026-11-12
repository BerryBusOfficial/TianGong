package org.wutong.tiangong.test;

import org.junit.jupiter.api.Test;
import org.wutong.tiangong.annotations.annotation.framework.Scheduled;
import org.wutong.tiangong.annotations.annotation.definition.BeanDefinition;
import org.wutong.tiangong.annotations.annotation.definition.Lifecycle;
import org.wutong.tiangong.core.scheduled.CronExpression;
import org.wutong.tiangong.core.scheduled.ScheduledAnnotationProcessor;
import org.wutong.tiangong.core.scheduled.ScheduledTaskDefinition;
import org.wutong.tiangong.core.scheduled.ScheduledTaskDefinition.Mode;
import org.wutong.tiangong.core.scheduled.ScheduledTaskRegistry;
import org.wutong.tiangong.core.scheduled.ScheduledTaskScheduler;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 定时任务测试：Cron 解析 + @Scheduled 注解处理 + 调度执行。
 */
class ScheduledTaskTest {

    // ==================== CronExpression ====================

    @Test
    void cronEverySecond() {
        CronExpression cron = new CronExpression("* * * * * *");
        ZonedDateTime now = ZonedDateTime.of(2026, 9, 11, 12, 0, 0, 0, ZoneId.systemDefault());
        ZonedDateTime next = cron.next(now);
        assertEquals(12, next.getHour());
        assertEquals(0, next.getMinute());
        assertEquals(1, next.getSecond(), "每秒触发，下一秒应匹配");
    }

    @Test
    void cronSpecificSecond() {
        CronExpression cron = new CronExpression("30 * * * * *");
        ZonedDateTime now = ZonedDateTime.of(2026, 9, 11, 12, 0, 0, 0, ZoneId.systemDefault());
        ZonedDateTime next = cron.next(now);
        assertEquals(30, next.getSecond(), "应在第 30 秒触发");
    }

    @Test
    void cronEvery15Seconds() {
        CronExpression cron = new CronExpression("*/15 * * * * *");
        ZonedDateTime now = ZonedDateTime.of(2026, 9, 11, 12, 0, 0, 0, ZoneId.systemDefault());
        ZonedDateTime next = cron.next(now);
        assertEquals(15, next.getSecond(), "应在第 15 秒触发");
        ZonedDateTime next2 = cron.next(next);
        assertEquals(30, next2.getSecond(), "下次应在第 30 秒");
    }

    @Test
    void cronListValues() {
        CronExpression cron = new CronExpression("0,15,30,45 * * * * *");
        ZonedDateTime now = ZonedDateTime.of(2026, 9, 11, 12, 0, 0, 0, ZoneId.systemDefault());
        ZonedDateTime next = cron.next(now);
        assertEquals(15, next.getSecond());
    }

    @Test
    void cronHourly() {
        CronExpression cron = new CronExpression("0 0 * * * *");
        ZonedDateTime now = ZonedDateTime.of(2026, 9, 11, 12, 30, 0, 0, ZoneId.systemDefault());
        ZonedDateTime next = cron.next(now);
        assertEquals(13, next.getHour(), "应在下一小时触发");
        assertEquals(0, next.getMinute());
        assertEquals(0, next.getSecond());
    }

    @Test
    void cronInvalidFields() {
        assertThrows(IllegalArgumentException.class, () -> new CronExpression("* * * *"));
        // ⚠ 7 段不再算非法：QUARTZ 方言的第 7 段是可选的「年」，
        //   旧的手写实现只认 6 段。换方言后这是能力扩展，见 cronSeventhFieldIsYear。
    }

    @Test
    void cronSeventhFieldIsYear() {
        // 秒 分 时 日 月 周 年 → 2027-01-01 09:00:00
        CronExpression cron = new CronExpression("0 0 9 1 1 ? 2027");
        ZonedDateTime next = cron.next(ZonedDateTime.now());
        assertEquals(2027, next.getYear());
        assertEquals(1, next.getMonthValue());
        assertEquals(1, next.getDayOfMonth());
    }

    // ==================== ScheduledTaskDefinition ====================

    @Test
    void fixedRateTaskDefinition() throws Exception {
        var method = TestTask.class.getDeclaredMethod("fixedRateTask");
        Scheduled ann = method.getAnnotation(Scheduled.class);
        ScheduledTaskDefinition task = ScheduledTaskDefinition.of("testTask", new TestTask(), method, ann);
        assertEquals(Mode.FIXED_RATE, task.mode());
        assertEquals(1000, task.interval());
        assertEquals(500, task.initialDelay());
    }

    @Test
    void fixedDelayTaskDefinition() throws Exception {
        var method = TestTask.class.getDeclaredMethod("fixedDelayTask");
        Scheduled ann = method.getAnnotation(Scheduled.class);
        ScheduledTaskDefinition task = ScheduledTaskDefinition.of("testTask", new TestTask(), method, ann);
        assertEquals(Mode.FIXED_DELAY, task.mode());
        assertEquals(2000, task.interval());
        assertEquals(0, task.initialDelay(), "未设置 initialDelay 时默认 0");
    }

    @Test
    void cronTaskDefinition() throws Exception {
        var method = TestTask.class.getDeclaredMethod("cronTask");
        Scheduled ann = method.getAnnotation(Scheduled.class);
        ScheduledTaskDefinition task = ScheduledTaskDefinition.of("testTask", new TestTask(), method, ann);
        assertEquals(Mode.CRON, task.mode());
        assertEquals("0 */5 * * * *", task.cron());
    }

    @Test
    void timeUnitConversion() throws Exception {
        var method = TestTask.class.getDeclaredMethod("secondsTask");
        Scheduled ann = method.getAnnotation(Scheduled.class);
        ScheduledTaskDefinition task = ScheduledTaskDefinition.of("testTask", new TestTask(), method, ann);
        assertEquals(Mode.FIXED_RATE, task.mode());
        assertEquals(3000, task.interval(), "3 SECONDS 应转为 3000 毫秒");
    }

    // ==================== ScheduledAnnotationProcessor ====================

    @Test
    void processorRegistersScheduledMethods() throws Exception {
        ScheduledTaskRegistry registry = new ScheduledTaskRegistry();
        ScheduledAnnotationProcessor processor = new ScheduledAnnotationProcessor(registry);
        TestTask instance = new TestTask();
        BeanDefinition def = BeanDefinition.builder()
                .beanName("testTask")
                .beanClass(TestTask.class)
                .lifecycle(Lifecycle.SINGLETON)
                .lazyInit(false)
                .build();
        processor.process(instance, def);
        assertEquals(4, registry.size(), "应注册 4 个 @Scheduled 方法");
    }

    @Test
    void processorRejectsParameterizedMethod() {
        ScheduledTaskRegistry registry = new ScheduledTaskRegistry();
        ScheduledAnnotationProcessor processor = new ScheduledAnnotationProcessor(registry);
        BadTask instance = new BadTask();
        BeanDefinition def = BeanDefinition.builder()
                .beanName("badTask")
                .beanClass(BadTask.class)
                .lifecycle(Lifecycle.SINGLETON)
                .lazyInit(false)
                .build();
        assertThrows(Exception.class, () -> processor.process(instance, def),
                "有参数的 @Scheduled 方法应被拒绝");
    }

    // ==================== ScheduledTaskScheduler ====================

    @Test
    void fixedRateExecution() throws Exception {
        CounterTask task = new CounterTask();
        var method = CounterTask.class.getDeclaredMethod("tick");
        Scheduled ann = method.getAnnotation(Scheduled.class);
        ScheduledTaskDefinition def = ScheduledTaskDefinition.of("counter", task, method, ann);

        ScheduledTaskRegistry registry = new ScheduledTaskRegistry();
        registry.register(def);

        ScheduledTaskScheduler scheduler = new ScheduledTaskScheduler();
        scheduler.start(registry);
        Thread.sleep(350);   // 100ms fixedRate，应执行约 3 次
        scheduler.close();

        assertTrue(task.count.get() >= 2, "应至少执行 2 次，实际: " + task.count.get());
    }

    @Test
    void fixedDelayExecution() throws Exception {
        CounterTask task = new CounterTask();
        var method = CounterTask.class.getDeclaredMethod("tickDelay");
        Scheduled ann = method.getAnnotation(Scheduled.class);
        ScheduledTaskDefinition def = ScheduledTaskDefinition.of("counter", task, method, ann);

        ScheduledTaskRegistry registry = new ScheduledTaskRegistry();
        registry.register(def);

        ScheduledTaskScheduler scheduler = new ScheduledTaskScheduler();
        scheduler.start(registry);
        Thread.sleep(550);   // 200ms fixedDelay + 100ms initialDelay
        scheduler.close();

        assertTrue(task.count.get() >= 1, "应至少执行 1 次");
    }

    @Test
    void cronExecution() throws Exception {
        CounterTask task = new CounterTask();
        // 每秒触发的 cron
        var method = CounterTask.class.getDeclaredMethod("tickCron");
        Scheduled ann = method.getAnnotation(Scheduled.class);
        ScheduledTaskDefinition def = ScheduledTaskDefinition.of("counter", task, method, ann);

        ScheduledTaskRegistry registry = new ScheduledTaskRegistry();
        registry.register(def);

        ScheduledTaskScheduler scheduler = new ScheduledTaskScheduler();
        scheduler.start(registry);
        Thread.sleep(2500);   // 每秒触发，等 2.5 秒
        scheduler.close();

        assertTrue(task.count.get() >= 1, "cron 任务应至少执行 1 次");
    }

    @Test
    void emptyRegistryStartsCleanly() {
        ScheduledTaskRegistry registry = new ScheduledTaskRegistry();
        ScheduledTaskScheduler scheduler = new ScheduledTaskScheduler();
        scheduler.start(registry);
        scheduler.close();
        assertTrue(true, "空注册表不应抛异常");
    }

    // ==================== 测试用类 ====================

    static class TestTask {
        @Scheduled(fixedRate = 1000, initialDelay = 500)
        public void fixedRateTask() {}

        @Scheduled(fixedDelay = 2000)
        public void fixedDelayTask() {}

        @Scheduled(cron = "0 */5 * * * *")
        public void cronTask() {}

        @Scheduled(fixedRate = 3, timeUnit = TimeUnit.SECONDS)
        public void secondsTask() {}
    }

    static class BadTask {
        @Scheduled(fixedRate = 1000)
        public void badTask(String param) {}
    }

    static class CounterTask {
        final AtomicInteger count = new AtomicInteger(0);

        @Scheduled(fixedRate = 100, initialDelay = 50)
        public void tick() {
            count.incrementAndGet();
        }

        @Scheduled(fixedDelay = 200, initialDelay = 100)
        public void tickDelay() {
            count.incrementAndGet();
        }

        @Scheduled(cron = "* * * * * *")
        public void tickCron() {
            count.incrementAndGet();
        }
    }
}
