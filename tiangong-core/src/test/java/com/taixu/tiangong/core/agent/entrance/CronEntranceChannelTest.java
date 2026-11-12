package com.taixu.tiangong.core.agent.entrance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.taixu.tiangong.common.util.JsonUtil;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CronEntranceChannel} 的行为测试：钉住"定时入口"最容易悄悄出错的几条语义。<br>
 * <br>
 * 每条用例都配了可观测的依据（计数 / 返回 null / 线程是否真的死了），
 * 不用"sleep 一下应该就跑了吧"这种自证式断言。
 */
class CronEntranceChannelTest {

    private CronEntranceChannel channel;

    private CronEntranceChannel newChannel() {
        channel = new CronEntranceChannel();
        return channel;
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.close();
        }
    }

    /** 每秒触发的 QUARTZ 表达式（秒 分 时 日 月 周；兼容层会把日归一化成 ?） */
    private static final String EVERY_SECOND = "* * * * * ?";

    @Test
    void awaitReturnsNullWhenNotSubscribed() throws Exception {
        CronEntranceChannel ch = newChannel();
        // 没有订阅 = 通道对该工作流已结束。返回 null 是执行器退出会话循环的依据。
        assertNull(ch.await("wf-none", new StubContext("wf-none")));
    }

    @Test
    void firesAndDeliversTick() throws Exception {
        CronEntranceChannel ch = newChannel();
        CronSubscription sub = ch.subscribe("wf-1", new String[]{EVERY_SECOND}, "UTC");

        CronTick tick = ch.await("wf-1", new StubContext("wf-1"));

        assertNotNull(tick, "每秒的 cron 必须在 1 秒出头内投递出第一拍");
        assertEquals(1L, tick.sequence(), "第一拍的序号是 1");
        assertEquals("UTC", tick.zone());
        assertTrue(tick.epochMillis() > 0);
        assertEquals(1L, sub.firedCount());
        assertEquals(EVERY_SECOND, sub.expressions().get(0));
    }

    @Test
    void tickBodyRoundTripsThroughJson() throws Exception {
        CronEntranceChannel ch = newChannel();
        CronTick tick = new CronTick("0 0 9 * * ?", "2026-09-20T09:00+08:00", 1234567890L, "Asia/Shanghai", 7L);

        String body = ch.toBody(tick);
        CronTick back = JsonUtil.fromJson(body, CronTick.class);

        // 往返必须无损：字段上的显式 @JsonProperty 就是为这条路径准备的，
        // JsonUtil 全局 SNAKE_CASE，不写显式名字时大小写策略一变就会静默取不到值。
        assertEquals(tick, back);
    }

    @Test
    void skipsTicksWhilePreviousNotConsumed() throws Exception {
        CronEntranceChannel ch = newChannel();
        CronSubscription sub = ch.subscribe("wf-skip", new String[]{EVERY_SECOND}, "UTC");

        // 3 秒不取：第 1 拍进队列，后面 2 拍都该被丢弃（不是排队等着重放）
        Thread.sleep(3000);

        assertTrue(sub.firedCount() >= 1, "至少投递过一次");
        assertTrue(sub.skippedCount() >= 1,
                "上一拍没被取走时，后续触发必须丢弃（实测 fired=" + sub.firedCount()
                        + ", skipped=" + sub.skippedCount() + "）");
        assertTrue(sub.hasPending());

        // 取走积压的那一拍后，队列必须是空的 —— 证明没有堆积
        assertNotNull(ch.await("wf-skip", new StubContext("wf-skip")));
        assertFalse(sub.hasPending(), "取走后不应还有积压：丢弃的就是丢弃了，不会攒着");
    }

    @Test
    void awaitReturnsNullAfterUnsubscribe() throws Exception {
        CronEntranceChannel ch = newChannel();
        ch.subscribe("wf-2", new String[]{EVERY_SECOND}, "UTC");
        ch.unsubscribe("wf-2");

        assertNull(ch.await("wf-2", new StubContext("wf-2")),
                "退订后 await 必须返回 null，否则工作流会永远挂在已死的订阅上");
        assertEquals(0, ch.subscriptionCount());
    }

    @Test
    void unsubscribeStopsWorkerSleepingFarAway() throws Exception {
        CronEntranceChannel ch = newChannel();
        // 每月最后一天 9 点 —— 睡到下一拍是一个月后；不打断就永远不会醒
        CronSubscription sub = ch.subscribe("wf-far", new String[]{"0 0 9 L * ?"}, "UTC");
        Thread worker = sub.worker();

        assertNotNull(worker, "订阅必须持有调度线程引用，否则取消订阅打不进中断");
        ch.unsubscribe("wf-far");

        worker.join(TimeUnit.SECONDS.toMillis(2));
        assertFalse(worker.isAlive(),
                "退订必须把睡在'下一拍'上的调度线程打断，否则线程与订阅一起泄漏");
    }

    @Test
    void invalidCronFailsFastAtSubscribe() {
        CronEntranceChannel ch = newChannel();
        // Quartz 不允许日与周同时给具体值
        assertThrows(IllegalArgumentException.class,
                () -> ch.subscribe("wf-bad", new String[]{"0 0 9 1 * MON"}, "UTC"),
                "错误的 cron 必须在订阅时就炸，静默降级 = 定时任务永远不跑且看不出原因");
    }

    @Test
    void emptyCronRejected() {
        CronEntranceChannel ch = newChannel();
        assertThrows(IllegalArgumentException.class,
                () -> ch.subscribe("wf-empty", new String[]{}, "UTC"));
        assertThrows(IllegalArgumentException.class,
                () -> ch.subscribe("wf-blank", new String[]{"  "}, "UTC"));
    }

    @Test
    void multipleExpressionsFireOnNearest() throws Exception {
        CronEntranceChannel ch = newChannel();
        // 两条每秒的表达式：取最近的一拍（多条之间是「或」）
        ch.subscribe("wf-multi", new String[]{EVERY_SECOND, EVERY_SECOND}, "UTC");

        CronTick tick = ch.await("wf-multi", new StubContext("wf-multi"));

        assertNotNull(tick);
        assertEquals(EVERY_SECOND, tick.expression());
    }
}
