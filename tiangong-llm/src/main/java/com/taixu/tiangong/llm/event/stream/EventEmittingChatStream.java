package com.taixu.tiangong.llm.event.stream;

import com.taixu.tiangong.llm.api.response.chat.chunk.ChatChunkResponse;
import com.taixu.tiangong.protocol.event.AgentEvent;
import com.taixu.tiangong.protocol.event.AgentEventScope;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 无工具请求的事件桥：把裸 {@link ChatStream} 的正文/思考/用量转发成类型化事件。<br>
 * <br>
 * <b>为什么需要单独的类</b>：{@link ToolLoopChatStream} 只在「带工具且装了循环工厂」时
 * 才出现；而纯对话同样是流式，同样需要 {@code message.delta} / {@code done}。
 * 用装饰器而不是改三个 provider 的流实现，保证事件语义只有一处。<br>
 * <br>
 * <b>事件消费者在构造时就挂到裸流上</b>：调用方可能只关心 {@code onComplete}、不注册
 * {@code onData}；事件仍应完整发出，不能把「发不发事件」绑定到「调用方是否订阅正文」。
 */
public final class EventEmittingChatStream<T, TCall> implements ChatStream<T, TCall> {

    private final ChatStream<T, TCall> delegate;
    private final Function<T, String> deltaExtractor;
    private final AgentEventScope.Binding binding;
    private final AtomicBoolean terminalEmitted = new AtomicBoolean();

    private final List<Consumer<? super T>> dataConsumers = new CopyOnWriteArrayList<>();
    private final List<Consumer<? super String>> reasoningConsumers = new CopyOnWriteArrayList<>();
    private final List<Consumer<? super TCall>> toolCallConsumers = new CopyOnWriteArrayList<>();
    private final List<Consumer<? super List<TCall>>> toolCallsConsumers = new CopyOnWriteArrayList<>();
    private final List<Consumer<? super ChatAggregateResult<TCall>>> finishConsumers = new CopyOnWriteArrayList<>();
    private final List<Runnable> completeConsumers = new CopyOnWriteArrayList<>();
    private final List<Consumer<? super Throwable>> errorConsumers = new CopyOnWriteArrayList<>();

    EventEmittingChatStream(ChatStream<T, TCall> delegate,
                            Function<T, String> deltaExtractor,
                            AgentEventScope.Binding binding) {
        this.delegate = delegate;
        this.deltaExtractor = deltaExtractor;
        this.binding = binding;
        wireEvents();
    }

    private void wireEvents() {
        delegate.onData(chunk -> {
            String delta = extractDelta(chunk);
            if (delta != null && !delta.isEmpty()) {
                AgentEventScope.emit(binding, new AgentEvent.MessageDelta(delta));
            }
            dataConsumers.forEach(consumer -> consumer.accept(chunk));
        });
        delegate.onReasoning(text -> {
            if (text != null && !text.isEmpty()) {
                AgentEventScope.emit(binding, new AgentEvent.ReasoningDelta(text));
            }
            reasoningConsumers.forEach(consumer -> consumer.accept(text));
        });
        delegate.onToolCall(call -> toolCallConsumers.forEach(consumer -> consumer.accept(call)));
        delegate.onToolCalls(calls -> toolCallsConsumers.forEach(consumer -> consumer.accept(calls)));
        delegate.onFinish(result -> {
            emitUsage(result);
            finishConsumers.forEach(consumer -> consumer.accept(result));
        });
        delegate.onComplete(() -> {
            emitTerminal("completed");
            completeConsumers.forEach(Runnable::run);
        });
        delegate.onError(error -> {
            emitError(error);
            errorConsumers.forEach(consumer -> consumer.accept(error));
        });
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
        return delegate.future();
    }

    @Override
    public CompletableFuture<List<T>> collect() {
        return delegate.collect();
    }

    @Override
    public CompletableFuture<ChatAggregateResult<TCall>> result() {
        return delegate.result();
    }

    private String extractDelta(T chunk) {
        if (chunk == null) {
            return null;
        }
        if (deltaExtractor != null) {
            return deltaExtractor.apply(chunk);
        }
        if (chunk instanceof ChatChunkResponse<?> response) {
            return response.getContent();
        }
        return null;
    }

    private void emitUsage(ChatAggregateResult<TCall> result) {
        if (result == null || result.usage() == null) {
            return;
        }
        ChatAggregateResult.TokenUsage usage = result.usage();
        AgentEventScope.emit(binding, new AgentEvent.Usage(
                usage.promptTokens(), usage.completionTokens(), usage.totalTokens(), null));
    }

    private void emitError(Throwable error) {
        if (terminalEmitted.compareAndSet(false, true)) {
            AgentEventScope.emit(binding, new AgentEvent.Error(
                    "llm_stream_error",
                    error == null ? "未知错误" : String.valueOf(error.getMessage()),
                    false));
            AgentEventScope.emit(binding, new AgentEvent.Done("failed"));
        }
    }

    private void emitTerminal(String status) {
        if (terminalEmitted.compareAndSet(false, true)) {
            AgentEventScope.emit(binding, new AgentEvent.Done(status));
        }
    }
}
