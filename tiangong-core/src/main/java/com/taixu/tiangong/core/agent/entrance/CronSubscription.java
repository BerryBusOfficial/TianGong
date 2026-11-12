package com.taixu.tiangong.core.agent.entrance;

import com.taixu.tiangong.core.scheduled.CronExpression;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一条 cron 订阅：属于某个工作流实例的 {@code @CronNode}。<br>
 * <br>
 * <b>容量 1 的队列是刻意的</b> —— 它同时实现了两条铁律：
 * <ul>
 *     <li><b>至多 1 个待触发</b>：上一拍没被取走（说明上一轮还没跑回等待点）时，
 *         {@code offer} 直接失败，本次触发丢弃</li>
 *     <li><b>不补发</b>：无论是否投递成功，下一次都从"当前时刻"往后算，
 *         不会因为积压而事后补跑</li>
 * </ul>
 */
public final class CronSubscription {

    private final String workflowId;
    private final List<CronExpression> expressions;
    private final ZoneId zone;

    /** 待触发队列：容量 1，满即丢弃（见类注释） */
    final BlockingQueue<CronTick> queue = new ArrayBlockingQueue<>(1);

    /** 订阅是否已停止（取消订阅 / 通道关闭） */
    final AtomicBoolean stopped = new AtomicBoolean(false);

    /**
     * 调度线程引用。<br>
     * 必须存下来：调度线程可能正睡在"下一拍"上（间隔是几小时甚至几天），
     * 只置停止标记要等睡醒才生效 —— 取消订阅会一直挂着不退出。
     */
    private volatile Thread worker;

    /** 因"上一拍未取走"而被丢弃的触发次数（监控用） */
    private final AtomicLong skipped = new AtomicLong(0);

    /** 已投递的触发次数 */
    private final AtomicLong fired = new AtomicLong(0);

    CronSubscription(String workflowId, List<CronExpression> expressions, ZoneId zone) {
        this.workflowId = workflowId;
        this.expressions = List.copyOf(expressions);
        this.zone = zone;
    }

    public String workflowId() {
        return workflowId;
    }

    /** 原始表达式文本（按声明顺序） */
    public List<String> expressions() {
        return expressions.stream().map(CronExpression::getExpression).toList();
    }

    public ZoneId zone() {
        return zone;
    }

    /** 已投递次数 */
    public long firedCount() {
        return fired.get();
    }

    /** 被丢弃次数（上一拍未消费） */
    public long skippedCount() {
        return skipped.get();
    }

    /** 当前是否有未被取走的触发 */
    public boolean hasPending() {
        return !queue.isEmpty();
    }

    /**
     * 绑定调度线程。必须在 {@code start()} <b>之前</b>调用，
     * 否则"刚建订阅就被取消"这一瞬间会漏掉中断，线程要睡到下一拍才醒。
     */
    void attachWorker(Thread worker) {
        this.worker = worker;
    }

    Thread worker() {
        return worker;
    }

    void markFired() {
        fired.incrementAndGet();
    }

    void markSkipped() {
        skipped.incrementAndGet();
    }

    /** 一次计划触发：时刻 + 命中的那条表达式 */
    record Plan(ZonedDateTime time, String expression) {
    }

    /**
     * 计算下一次触发计划：取所有表达式里<b>最近</b>的那一拍（多条表达式之间是「或」）。
     *
     * @param now 当前时刻（须为本订阅时区）
     * @return 下一次触发计划；表达式列表为空时返回 null
     */
    Plan nextPlan(ZonedDateTime now) {
        ZonedDateTime next = null;
        String hit = null;
        for (CronExpression expr : expressions) {
            ZonedDateTime candidate = expr.next(now);
            if (next == null || candidate.isBefore(next)) {
                next = candidate;
                hit = expr.getExpression();
            }
        }
        return next == null ? null : new Plan(next, hit);
    }
}
