package org.wutong.tiangong.core.agent.entrance;

import lombok.extern.slf4j.Slf4j;
import org.wutong.tiangong.common.agent.context.AgentContext;
import org.wutong.tiangong.common.thread.VirtualThreads;
import org.wutong.tiangong.common.util.JsonUtil;
import org.wutong.tiangong.core.scheduled.CronExpression;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 定时入口通道：{@code @CronNode} 背后的"取输入"实现。<br>
 * <br>
 * <h2>工作模型</h2>
 * <ul>
 *     <li>执行器进入 {@code @CronNode} 时调 {@link #subscribe} 建立订阅，
 *         通道为该工作流起一条虚拟线程算下一拍并到点投递；退出时调
 *         {@link #unsubscribe} 停掉</li>
 *     <li>{@link #await(String, AgentContext)} 阻塞取一次触发（200ms 轮询，
 *         保证取消/关闭信号能打进来）；<b>未订阅时返回 null</b>，执行器据此退出会话循环</li>
 *     <li>使用模式 {@code SESSION_LOOP}：取一拍 → 跑一轮到 END → 回到本节点等待下一拍</li>
 * </ul>
 *
 * <h2>三条触发铁律（见 {@code CronSubscription} 与 {@code @CronNode} 文档）</h2>
 * 不重叠 / 不补发 / 至多 1 个待触发。<b>"不补发"的实现方式是：每次投递之后都从
 * "当前时刻"重新求下一拍</b> —— 于是一轮跑得再久，错过的那些拍也不会堆积起来事后补跑。
 *
 * <h2>与 {@code core.scheduled} 的分工</h2>
 * 本通道只借 {@link CronExpression} 算时刻，<b>不复用</b>
 * {@code ScheduledTaskScheduler}：那是"直接调一个 Bean 方法"的定时任务，
 * 这里是"唤醒一条阻塞中的工作流"，两者的生命周期与并发账务完全不同。
 */
@Slf4j
public class CronEntranceChannel implements EntranceChannel<CronTick> {

    /** await 轮询粒度：决定取消/关闭信号的最迟响应延迟，也决定触发的最坏延迟 */
    static final long POLL_MILLIS = 200L;

    /** workflowId → 订阅 */
    private final ConcurrentMap<String, CronSubscription> subscriptions = new ConcurrentHashMap<>();

    private final AtomicBoolean closing = new AtomicBoolean(false);

    @Override
    public String name() {
        return "cron";
    }

    /**
     * 建立订阅并立即启动调度。<br>
     * <br>
     * 同一 workflowId 重复订阅 = 替换（旧订阅停止，打 WARN）。
     *
     * @param workflowId  工作流实例 ID
     * @param expressions cron 表达式（QUARTZ 方言，6 段）
     * @param zone        时区文本；null / 空表示系统默认时区
     * @return 新建的订阅（可查 {@code firedCount()} / {@code skippedCount()}）
     */
    public CronSubscription subscribe(String workflowId, String[] expressions, String zone) {
        if (workflowId == null || workflowId.isEmpty()) {
            throw new IllegalArgumentException("cron 订阅需要 workflowId");
        }
        if (expressions == null || expressions.length == 0) {
            throw new IllegalArgumentException(
                    "cron 订阅 [" + workflowId + "] 未给出任何表达式；@CronNode(cron = {...}) 不能为空");
        }
        List<CronExpression> parsed = new ArrayList<>(expressions.length);
        for (String expr : expressions) {
            if (expr == null || expr.isBlank()) {
                throw new IllegalArgumentException(
                        "cron 订阅 [" + workflowId + "] 含有空表达式");
            }
            // 构造失败（方言不合法）由 CronExpression 抛 IllegalArgumentException，直接冒泡：
            // 错误的 cron 必须在启动期炸出来，静默降级 = 定时任务永远不跑且看不出原因。
            parsed.add(new CronExpression(expr.trim()));
        }
        ZoneId zoneId = (zone == null || zone.isEmpty())
                ? ZoneId.systemDefault()
                : ZoneId.of(zone);

        CronSubscription sub = new CronSubscription(workflowId, parsed, zoneId);
        CronSubscription prev = subscriptions.put(workflowId, sub);
        if (prev != null) {
            log.warn("工作流 [{}] 重复订阅 cron，旧订阅已停止", workflowId);
            stop(prev);
        }
        startWorker(sub);
        log.debug("cron 订阅已建立: workflow={} cron={} zone={}", workflowId, List.of(expressions), zoneId);
        return sub;
    }

    /**
     * 取消订阅：停止调度线程并丢弃未取走的触发。<br>
     * 幂等 —— 重复调用或未订阅都安全。
     */
    public void unsubscribe(String workflowId) {
        CronSubscription removed = subscriptions.remove(workflowId);
        if (removed != null) {
            stop(removed);
            log.debug("cron 订阅已取消: workflow={}", workflowId);
        }
    }

    /** 当前订阅数（监控/调试） */
    public int subscriptionCount() {
        return subscriptions.size();
    }

    /** 取得订阅（未订阅返回 null） */
    public CronSubscription subscription(String workflowId) {
        return subscriptions.get(workflowId);
    }

    @Override
    public void close() {
        closing.set(true);
        for (CronSubscription sub : subscriptions.values()) {
            stop(sub);
        }
        subscriptions.clear();
        log.info("定时通道已关闭：等待中的 await 将在 ≤ {}ms 内退出", POLL_MILLIS);
    }

    @Override
    public CronTick await(String workflowId, AgentContext context) throws InterruptedException {
        CronSubscription sub = subscriptions.get(workflowId);
        // 未订阅 = 通道对该工作流已结束（订阅被取消或通道已关闭）→ 通知执行器退出会话循环。
        // 不抛异常：这是正常的生命周期终点，不是错误。
        if (sub == null) {
            return null;
        }
        while (!closing.get() && !context.isCancelled()) {
            CronTick tick = sub.queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
            if (tick != null) {
                return tick;
            }
            // 超时：回到循环顶部重新检查关闭/取消/是否仍在订阅中
            if (!subscriptions.containsKey(workflowId)) {
                return null;
            }
        }
        return null;
    }

    @Override
    public String toBody(CronTick input) {
        try {
            return JsonUtil.toJson(input);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("cron 触发载荷序列化失败", e);
        }
    }

    // isJsonBody 用接口默认 true —— CronTick 转 JSON 后按节点方法参数类型反序列化

    // ==================== 调度线程 ====================

    private void startWorker(CronSubscription sub) {
        // 先 unstarted 绑定再 start：避免"刚订阅就被取消"时拿不到线程引用、
        // 中断打不进去（下一拍可能是几天后）。
        Thread worker = VirtualThreads.unstarted(() -> runLoop(sub));
        sub.attachWorker(worker);
        worker.start();
    }

    /**
     * 单条订阅的调度循环：算下一拍 → 睡到点 → 投递 → 再从"当前时刻"算下一拍。<br>
     * <br>
     * 投递失败（上一拍未取走）不算错误，计数后继续 —— 这正是"至多 1 个待触发 + 不补发"。
     */
    private void runLoop(CronSubscription sub) {
        long sequence = 0;
        try {
            while (!sub.stopped.get() && !closing.get()) {
                ZonedDateTime now = ZonedDateTime.now(sub.zone());
                CronSubscription.Plan plan = sub.nextPlan(now);
                if (plan == null) {
                    return;
                }
                long sleepMillis = Duration.between(now, plan.time()).toMillis();
                if (sleepMillis > 0) {
                    try {
                        Thread.sleep(sleepMillis);
                    } catch (InterruptedException e) {
                        // 只在"被停止/关闭"时是真的要退出；否则是外部误打扰，重算继续
                        if (sub.stopped.get() || closing.get()) {
                            return;
                        }
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (sub.stopped.get() || closing.get()) {
                    return;
                }
                sequence++;
                CronTick tick = new CronTick(
                        plan.expression(),
                        plan.time().toString(),
                        plan.time().toInstant().toEpochMilli(),
                        sub.zone().getId(),
                        sequence);
                if (sub.queue.offer(tick)) {
                    sub.markFired();
                    log.debug("cron 触发: workflow={} expr={} at={}",
                            sub.workflowId(), plan.expression(), plan.time());
                } else {
                    sub.markSkipped();
                    log.debug("cron 触发被丢弃（上一拍尚未消费）: workflow={} expr={}",
                            sub.workflowId(), plan.expression());
                }
            }
        } catch (Throwable t) {
            // 调度线程是后台设施，不能因为一次求值异常把整个线程悄悄弄死而不留痕
            log.error("cron 调度线程异常终止: workflow={}", sub.workflowId(), t);
        }
    }

    private void stop(CronSubscription sub) {
        sub.stopped.set(true);
        // 打断睡眠：调度线程可能正睡在几天后的下一拍上，不打断就永远不会醒来检查标记
        Thread worker = sub.worker();
        if (worker != null) {
            worker.interrupt();
        }
    }
}
