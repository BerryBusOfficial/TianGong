package org.wutong.tiangong.test;

import org.junit.jupiter.api.Test;
import org.wutong.tiangong.annotations.annotation.framework.agent.interfaces.EventDrivenStartCondition;
import org.wutong.tiangong.annotations.annotation.framework.agent.interfaces.StartCondition;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StartCondition 体系测试。<br>
 * 覆盖：DEFAULT 条件、pollInterval 默认值、EventDrivenStartCondition 回调触发。
 */
class StartConditionTest {

    @Test
    void defaultConditionAlwaysTrue() {
        assertTrue(StartCondition.DEFAULT.condition());
    }

    @Test
    void defaultPollInterval() {
        assertEquals(Duration.ofMillis(500), StartCondition.DEFAULT.pollInterval());
    }

    @Test
    void customCondition() {
        StartCondition cond = new StartCondition() {
            @Override public boolean condition() { return true; }
            @Override public Duration pollInterval() { return Duration.ofSeconds(1); }
        };
        assertTrue(cond.condition());
        assertEquals(Duration.ofSeconds(1), cond.pollInterval());
    }

    @Test
    void eventDrivenConditionCallback() throws Exception {
        AtomicBoolean triggered = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        EventDrivenStartCondition ed = new EventDrivenStartCondition() {
            @Override
            public void onTrigger(Runnable callback) {
                // 模拟外部事件：异步触发
                new Thread(() -> {
                    try { Thread.sleep(50); } catch (InterruptedException e) {}
                    callback.run();
                }).start();
            }
        };

        // 注册回调
        ed.onTrigger(() -> {
            triggered.set(true);
            latch.countDown();
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS), "回调应在 2s 内被触发");
        assertTrue(triggered.get());
        // EventDrivenStartCondition.condition() 默认返回 false（不走轮询）
        assertFalse(ed.condition());
    }
}
