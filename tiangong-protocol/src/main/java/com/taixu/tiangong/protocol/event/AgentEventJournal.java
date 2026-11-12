package com.taixu.tiangong.protocol.event;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 内存事件日志：给一次运行分配 seq、支持断点回放与实时订阅。<br>
 * <br>
 * <h2>它解决的两件事</h2>
 * <ol>
 *     <li><b>序号</b>：SSE 的 {@code id} 必须单调递增；调用方只管发业务事件，
 *         序号由这里统一分配，避免各层各写一个计数器；</li>
 *     <li><b>续传</b>：客户端带 {@code Last-Event-ID} 重连时，先回放
 *         {@code seq > last} 的事件，再挂到实时订阅上——不是让客户端自己重放正文。</li>
 * </ol>
 * <br>
 * <b>边界</b>：这是内存实现，运行结束、进程退出即丢失。跨进程/跨重启的持久化回放
 * 属于检查点存储的职责，不在本类假装支持。
 */
public final class AgentEventJournal implements AgentEventSink {

    private final String runId;
    private final String turnId;
    private final long startedAt = System.currentTimeMillis();

    private final Object lock = new Object();
    private final List<AgentEventEnvelope> events = new ArrayList<>();
    private final List<Consumer<AgentEventEnvelope>> subscribers = new CopyOnWriteArrayList<>();

    private long nextSeq = 1;
    private boolean terminal;
    private Runnable cancelAction = () -> {
    };

    public AgentEventJournal(String runId, String turnId) {
        this.runId = Objects.requireNonNull(runId, "runId");
        this.turnId = turnId == null ? "" : turnId;
    }

    public String runId() {
        return runId;
    }

    public String turnId() {
        return turnId;
    }

    public long startedAt() {
        return startedAt;
    }

    public boolean isTerminal() {
        synchronized (lock) {
            return terminal;
        }
    }

    public int size() {
        synchronized (lock) {
            return events.size();
        }
    }

    /** {@link AgentEventSink} 实现：忽略返回值，供作用域/旁路统一调用 */
    @Override
    public void emit(AgentEvent event) {
        append(event);
    }

    /**
     * 追加一个事件并分配序号。<br>
     * 终止（{@link AgentEvent.Done}）之后到达的事件被丢弃：客户端已经收到 done，
     * 再补事件只会让它困惑，也破坏「done 是最后一条」的契约。
     *
     * @return 分配好 seq 的信封；已终止时返回 null
     */
    public AgentEventEnvelope append(AgentEvent event) {
        if (event == null) {
            return null;
        }
        AgentEventEnvelope envelope;
        List<Consumer<AgentEventEnvelope>> targets;
        synchronized (lock) {
            if (terminal) {
                return null;
            }
            envelope = new AgentEventEnvelope(nextSeq++, runId, turnId,
                    System.currentTimeMillis(), event);
            events.add(envelope);
            if (event instanceof AgentEvent.Done) {
                terminal = true;
            }
            targets = List.copyOf(subscribers);
        }
        notifySubscribers(targets, envelope);
        return envelope;
    }

    private static void notifySubscribers(List<Consumer<AgentEventEnvelope>> targets,
                                          AgentEventEnvelope envelope) {
        for (Consumer<AgentEventEnvelope> subscriber : targets) {
            try {
                subscriber.accept(envelope);
            } catch (RuntimeException ignored) {
                // 单个订阅者故障不能拖垮整条运行；SSE 写失败由写端自己感知并取消
            }
        }
    }

    /**
     * 回放 {@code seq > afterSeq} 的事件并订阅后续事件。<br>
     * 回放与「加入订阅者」在同一把锁内完成，保证不会出现「实时事件先到、旧事件后到」
     * 的乱序；订阅者在锁内回调，写端应尽快把事件交给缓冲区而不是阻塞在网络上。
     */
    public Subscription subscribe(Consumer<AgentEventEnvelope> consumer, long afterSeq) {
        Objects.requireNonNull(consumer, "consumer");
        synchronized (lock) {
            for (AgentEventEnvelope envelope : events) {
                if (envelope.seq() > afterSeq) {
                    try {
                        consumer.accept(envelope);
                    } catch (RuntimeException ignored) {
                        // 同上：回放阶段的单订阅者异常不改变日志状态
                    }
                }
            }
            if (!terminal) {
                subscribers.add(consumer);
            }
        }
        return () -> subscribers.remove(consumer);
    }

    /** 只回放，不订阅；供非流式查询/测试使用 */
    public List<AgentEventEnvelope> replayAfter(long afterSeq) {
        synchronized (lock) {
            List<AgentEventEnvelope> out = new ArrayList<>();
            for (AgentEventEnvelope envelope : events) {
                if (envelope.seq() > afterSeq) {
                    out.add(envelope);
                }
            }
            return out;
        }
    }

    public List<AgentEventEnvelope> all() {
        return replayAfter(0);
    }

    /** 设置客户端断开时的取消动作（关闭上游流/中断工作流） */
    public void onCancel(Runnable cancelAction) {
        this.cancelAction = cancelAction == null ? () -> {
        } : cancelAction;
    }

    /**
     * 取消运行：先通知上游收尾，再补一个 {@code done=cancelled}。<br>
     * 已经终止时幂等返回 false。
     */
    public boolean cancel() {
        if (isTerminal()) {
            return false;
        }
        try {
            cancelAction.run();
        } catch (RuntimeException ignored) {
            // 上游取消失败不改变「对客户端而言已取消」的语义
        }
        complete("cancelled");
        return true;
    }

    /** 正常结束 */
    public void complete(String status) {
        append(new AgentEvent.Done(status == null ? "completed" : status));
    }

    /** 失败收尾：先 error 再 done=failed；客户端不需要靠连接断开判断失败 */
    public void fail(String code, String message, boolean retryable) {
        if (isTerminal()) {
            return;
        }
        append(new AgentEvent.Error(code, message, retryable));
        complete("failed");
    }

    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
