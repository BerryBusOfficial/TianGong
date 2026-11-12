package com.taixu.tiangong.core.scheduled;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁住 cron 方言（{@code CronExpression}）。<br>
 * <br>
 * 这里是全框架唯一的 cron 入口（{@code @Scheduled} 与将来的 {@code @CronNode} 共用），
 * 而且错起来是**静默**的：表达式写歪不报错，只表现为「该触发的时候不触发」。<br>
 * <br>
 * 方言已从手写实现换成 <b>cron-utils 的 QUARTZ</b>。下面几条用例专门钉住那些
 * 「换方言会悄悄改变行为」的点 —— 它们是最容易在升级时被忽略的：
 * <ul>
 *     <li>日/周必须有一个是 {@code ?}（Quartz 不允许两个都指定）</li>
 *     <li>周日是 <b>1</b> 不是 0（Quartz 编号，与 Unix cron 相反）</li>
 *     <li>日字段的 {@code L} 现在能正确命中 30 天的月份（旧手写实现做不到）</li>
 * </ul>
 */
class CronExpressionTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 2026-09-20 是周日 */
    private static final ZonedDateTime SUNDAY_1000 =
            ZonedDateTime.of(2026, 9, 20, 10, 0, 0, 0, ZONE);

    private static String next(String expression) {
        return new CronExpression(expression).next(SUNDAY_1000).toLocalDateTime().toString();
    }

    @Test
    @DisplayName("步长：每 5 分钟（秒 0）")
    void everyFiveMinutes() {
        assertEquals("2026-09-20T10:05", next("0 */5 * * * ?"));
    }

    @Test
    @DisplayName("每天固定点：当天 10:00 之后要等第二天 09:00")
    void dailyAtNine() {
        assertEquals("2026-09-21T09:00", next("0 0 9 * * ?"));
    }

    @Test
    @DisplayName("工作日：周日（基准）往后推到周一 18:30")
    void weekdaysAtEighteenThirty() {
        assertEquals("2026-09-21T18:30", next("0 30 18 ? * MON-FRI"));
    }

    @Test
    @DisplayName("每月 1 号 09:00")
    void firstDayOfMonth() {
        assertEquals("2026-10-01T09:00", next("0 0 9 1 * ?"));
    }

    @Test
    @DisplayName("★ 周日是 1，不是 Unix cron 的 0（Quartz 编号）")
    void sundayIsOneNotZero() {
        // Quartz: SUN=1 MON=2 ... SAT=7。写成 0 是越界的，必须报错而不是"永远不触发"。
        assertThrows(IllegalArgumentException.class, () -> new CronExpression("0 0 9 ? * 0"));
        // 周日=1：基准就是周日 10:00，已过 9 点 → 下一个周日
        assertEquals("2026-09-27T09:00", next("0 0 9 ? * 1"));
        // 周一=2
        assertEquals("2026-09-21T09:00", next("0 0 9 ? * 2"));
    }

    @Test
    @DisplayName("★ 日与周都是具体值时报错：想要「或」必须拆成两条表达式")
    void dayOfMonthAndDayOfWeekTogetherIsRejected() {
        // 旧手写实现允许两个都写、按 AND 处理（`0 0 9 1 * MON` → 2027-02-01）。
        // QUARTZ 不接受这种写法，且 CronExpression 刻意不为它做归一化：
        // 这种写法本身就歧义（1 号 AND 周一？还是 OR？），宁可启动期炸，
        // 也不要留一个静默的歧义语义。想要「或」就写两条表达式。
        assertThrows(IllegalArgumentException.class, () -> new CronExpression("0 0 9 1 * MON"));
    }

    @Test
    @DisplayName("★ 兼容层：日/周都写 * 的 Unix 习惯写法要能继续用")
    void starStarIsNormalized() {
        // 项目里既有写法（如 `0 */5 * * * *`，日与周都是 *）在 Quartz 下本是非法的，
        // CronExpression 会归一成 `0 */5 * * * ?`。换方言不能把这些存量表达式炸掉。
        assertEquals("2026-09-20T10:05", next("0 */5 * * * *"));
        assertEquals("2026-09-21T09:00", next("0 0 9 * * *"));
        // 只有周是 *：归一成按日生效（每月 1 号）
        assertEquals("2026-10-01T09:00", next("0 0 9 1 * *"));
        // 只有日是 *：归一成按周生效（周一 = 2）
        assertEquals("2026-09-21T09:00", next("0 0 9 * * 2"));
    }

    @Test
    @DisplayName("★ L 现在能正确命中 30 天的月份（旧实现会跳过 9 月）")
    void lastDayOfMonth() {
        // 旧手写实现：L 被当成固定日号 31，9 月（30 天）被整个跳过 → 返回 2026-10-31。
        // 换 cron-utils 后应当是 9 月最后一天 9/30。
        assertEquals("2026-09-30T09:00", next("0 0 9 L * ?"));
        // 顺带确认 2 月这种短月也对
        assertEquals("2027-02-28T09:00",
                new CronExpression("0 0 9 L 2 ?")
                        .next(ZonedDateTime.of(2027, 1, 1, 0, 0, 0, 0, ZONE))
                        .toLocalDateTime().toString());
    }

    @Test
    @DisplayName("非法表达式在构造期就炸，不能等该触发才发现")
    void invalidExpressionsFailFast() {
        assertThrows(IllegalArgumentException.class, () -> new CronExpression("0 0 9 * *"));   // 5 段
        assertThrows(IllegalArgumentException.class, () -> new CronExpression(""));            // 空
        assertThrows(IllegalArgumentException.class, () -> new CronExpression("   "));         // 空白
        assertThrows(IllegalArgumentException.class, () -> new CronExpression("0 0 25 * * ?")); // 小时越界
    }

    @Test
    @DisplayName("连续取 3 次必须严格递增，且不会停在原地")
    void successiveFiresAdvance() {
        CronExpression expr = new CronExpression("0 */15 * * * ?");
        ZonedDateTime t1 = expr.next(SUNDAY_1000);
        ZonedDateTime t2 = expr.next(t1);
        ZonedDateTime t3 = expr.next(t2);
        assertTrue(t1.isBefore(t2) && t2.isBefore(t3), "连续触发时刻必须严格递增");
        assertEquals("2026-09-20T10:15", t1.toLocalDateTime().toString());
        assertEquals("2026-09-20T10:30", t2.toLocalDateTime().toString());
        assertEquals("2026-09-20T10:45", t3.toLocalDateTime().toString());
    }

    @Test
    @DisplayName("next() 是严格大于：基准恰好是触发点时不能返回基准本身")
    void nextIsStrictlyAfter() {
        // 基准 10:00:00 本身是 `0 */15 * * * ?` 的一个触发点
        CronExpression expr = new CronExpression("0 */15 * * * ?");
        ZonedDateTime onTheDot = ZonedDateTime.of(2026, 9, 20, 10, 0, 0, 0, ZONE);
        assertTrue(expr.next(onTheDot).isAfter(onTheDot),
                "next() 必须严格晚于基准，否则工作流会在同一时刻反复触发");
    }

    @Test
    @DisplayName("时区生效：cron 的时刻按各自时区的当地时间来算")
    void zoneIsRespected() {
        CronExpression expr = new CronExpression("0 0 9 * * ?");
        // 上海 10:00 与 UTC 02:00 是同一瞬间；但「每天 9 点」是各自的当地 9 点，
        // 所以两边拿到的瞬时不同、当地时刻相同 —— 这正是时区生效该有的样子。
        ZonedDateTime shanghai = expr.next(ZonedDateTime.of(2026, 9, 20, 10, 0, 0, 0, ZoneId.of("Asia/Shanghai")));
        ZonedDateTime utc = expr.next(ZonedDateTime.of(2026, 9, 20, 2, 0, 0, 0, ZoneId.of("UTC")));

        assertEquals("2026-09-21T09:00", shanghai.toLocalDateTime().toString());
        assertEquals("2026-09-20T09:00", utc.toLocalDateTime().toString());
        assertEquals(16L, Duration.between(utc.toInstant(), shanghai.toInstant()).toHours());
    }
}
