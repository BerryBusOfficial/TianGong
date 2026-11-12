package com.taixu.tiangong.llm.event.stream;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 推理预算流包装：统计 reasoning delta 并超过上限时取消上游、以异常结束。<br>
 * <br>
 * <b>为什么按字符估算</b>：框架不内置厂商 tokenizer（不同模型分词器不同，硬编码一个反而
 * 给出假精确）。这里用字符数作保守上界（中文约 1 字 ≈ 1 token，英文会高估），
 * 宁可早停也不放任模型把预算烧在思考上；需要精确计费仍以 provider usage 为准。<br>
 * <br>
 * 触发后不再转发后续 reasoning/正文，避免「已经取消却还在往 UI 吐字」；错误走
 * {@link #onError}，事件桥会据此发 error/done。
 */
public final class ReasoningBudgetChatStream<T, TCall> implements ChatStream<T, TCall> {

    private final ChatStream<T, TCall> delegate;
    private final int maxReasoningTokens;
    private final AtomicBoolean terminal = new AtomicBoolean();

    private final List<Consumer<? super T>> dataConsumers = new CopyOnWriteArrayList<>();
    private final List<Consumer<? super String>> reasoningConsumers = new CopyOnWriteArrayList<>();
    private final List<Consumer<? super TCall>> toolCallConsumers = new CopyOnWriteArrayList<>();
    private final List<Consumer<? super List<TCall>>> toolCallsConsumers = new CopyOnWriteArrayList<>();
    private final List<Consumer<? super ChatAggregateResult<TCall>>> finishConsumers = new CopyOnWriteArrayList<>();
    private final List<Runnable> completeConsumers = new CopyOnWriteArrayList<>();
    private final List<Consumer<? super Throwable>> errorConsumers = new CopyOnWriteArrayList<>();

    private final CompletableFuture<Void> future = new CompletableFuture<>();
    private final CompletableFuture<List<T>> collectFuture = new CompletableFuture<>();
    private final CompletableFuture<ChatAggregateResult<TCall>> resultFuture = new CompletableFuture<>();

    private long observedReasoningTokens;

    public ReasoningBudgetChatStream(ChatStream<T, TCall> delegate, int maxReasoningTokens) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate 不能为空");
        }
        if (maxReasoningTokens <= 0) {
            throw new IllegalArgumentException("maxReasoningTokens 必须为正数");
        }
        this.delegate = delegate;
        this.maxReasoningTokens = maxReasoningTokens;
        wire();
    }

    private void wire() {
        delegate.onData(chunk -> {
            if (!terminal.get()) {
                dataConsumers.forEach(consumer -> consumer.accept(chunk));
            }
        });
        delegate.onReasoning(text -> {
            if (terminal.get() || text == null || text.isEmpty()) {
                return;
            }
            observedReasoningTokens += estimate(text);
            if (observedReasoningTokens > maxReasoningTokens) {
                failBudget();
                return;
            }
            reasoningConsumers.forEach(consumer -> consumer.accept(text));
        });
        delegate.onToolCall(call -> {
            if (!terminal.get()) {
                toolCallConsumers.forEach(consumer -> consumer.accept(call));
            }
        });
        delegate.onToolCalls(calls -> {
            if (!terminal.get()) {
                toolCallsConsumers.forEach(consumer -> consumer.accept(calls));
            }
        });
        delegate.onFinish(result -> {
            if (!terminal.get()) {
                finishConsumers.forEach(consumer -> consumer.accept(result));
            }
        });
        delegate.onComplete(() -> {
            if (!terminal.get()) {
                delegate.collect().whenComplete((chunks, collectError) ->
                        delegate.result().whenComplete((result, resultError) -> {
                            if (collectError != null || resultError != null) {
                                fail(collectError != null ? collectError : resultError);
                                return;
                            }
                            if (terminal.compareAndSet(false, true)) {
                                collectFuture.complete(chunks);
                                resultFuture.complete(result);
                                completeConsumers.forEach(Runnable::run);
                                future.complete(null);
                            }
                        }));
            }
        });
        delegate.onError(error -> {
            if (!terminal.get()) {
                fail(error);
            }
        });
    }

    private void failBudget() {
        if (terminal.compareAndSet(false, true)) {
            delegate.cancel();
            ReasoningBudgetExceededException error =
                    new ReasoningBudgetExceededException(maxReasoningTokens, observedReasoningTokens);
            collectFuture.completeExceptionally(error);
            resultFuture.completeExceptionally(error);
            errorConsumers.forEach(consumer -> consumer.accept(error));
            future.completeExceptionally(error);
        }
    }

    private void fail(Throwable error) {
        if (terminal.compareAndSet(false, true)) {
            collectFuture.completeExceptionally(error);
            resultFuture.completeExceptionally(error);
            errorConsumers.forEach(consumer -> consumer.accept(error));
            future.completeExceptionally(error);
        }
    }

    /** 近似 token：字符数上界，见类注释 */
    private static long estimate(String text) {
        return text.length();
    }

    @Override
    public ChatStream<T, TCall> onData(Consumer<? super T> consumer) {
        dataConsumers.add(consumer);
        return this;
    }

    @Override
    public ChatStream<T, TCall> onReasoning(Consumer<? super String> consumer) {
        reasoningConsumers.add(consumer);
        return this;
    }

    @Override
    public ChatStream<T, TCall> onToolCall(Consumer<? super TCall> consumer) {
        toolCallConsumers.add(consumer);
        return this;
    }

    @Override
    public ChatStream<T, TCall> onToolCalls(Consumer<? super List<TCall>> consumer) {
        toolCallsConsumers.add(consumer);
        return this;
    }

    @Override
    public ChatStream<T, TCall> onFinish(Consumer<? super ChatAggregateResult<TCall>> consumer) {
        finishConsumers.add(consumer);
        return this;
    }

    @Override
    public ChatStream<T, TCall> onComplete(Runnable consumer) {
        completeConsumers.add(consumer);
        return this;
    }

    @Override
    public ChatStream<T, TCall> onError(Consumer<? super Throwable> consumer) {
        errorConsumers.add(consumer);
        return this;
    }

    @Override
    public ChatStream<T, TCall> start() {
        delegate.start();
        return this;
    }

    @Override
    public void cancel() {
        delegate.cancel();
    }

    @Override
    public CompletableFuture<Void> future() {
        return future;
    }

    @Override
    public CompletableFuture<List<T>> collect() {
        return collectFuture;
    }

    @Override
    public CompletableFuture<ChatAggregateResult<TCall>> result() {
        return resultFuture;
    }
}
