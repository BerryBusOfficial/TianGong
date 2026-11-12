package com.taixu.tiangong.llm.event.stream;

import com.taixu.tiangong.llm.api.request.chat.ChatRequest;
import com.taixu.tiangong.llm.message.HumanMessage;
import com.taixu.tiangong.protocol.event.AgentEvent;
import com.taixu.tiangong.protocol.event.AgentEventEnvelope;
import com.taixu.tiangong.protocol.event.AgentEventJournal;
import com.taixu.tiangong.protocol.event.AgentEventScope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventBridgeTest {

    @Test
    void plainStreamEmitsTypedDeltasUsageAndDone() {
        ToolLoopSupport.install(null);
        AgentEventJournal journal = new AgentEventJournal("run-1", "turn-1");
        AgentEventScope.Binding binding = new AgentEventScope.Binding(journal, "run-1", "turn-1");
        ChatRequest request = ChatRequest.builder()
                .model("test")
                .messages(List.of(new HumanMessage("hi")))
                .build();

        FakeStream raw = new FakeStream();
        AgentEventScope.runWith(binding, () -> {
            ChatStream<String, String> stream = ToolLoopSupport.wrap(
                    raw, request, null, call -> null, delta -> delta);
            stream.start().await();
        });

        List<AgentEvent> events = journal.all().stream()
                .map(AgentEventEnvelope::event)
                .toList();
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.MessageDelta delta
                && "hello".equals(delta.delta())));
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ReasoningDelta delta
                && "think".equals(delta.delta())));
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.Usage usage
                && usage.totalTokens() == 3));
        assertEquals(new AgentEvent.Done("completed"), events.getLast());
    }

    /**
     * 最简裸流：一次 data("hello")、一次 reasoning("think")、结束。
     */
    static final class FakeStream implements ChatStream<String, String> {

        private final List<Consumer<? super String>> dataConsumers = new CopyOnWriteArrayList<>();
        private final List<Consumer<? super String>> reasoningConsumers = new CopyOnWriteArrayList<>();
        private final List<Consumer<? super ChatAggregateResult<String>>> finishConsumers = new CopyOnWriteArrayList<>();
        private final List<Runnable> completeConsumers = new CopyOnWriteArrayList<>();
        private final CompletableFuture<Void> future = new CompletableFuture<>();
        private final AtomicBoolean started = new AtomicBoolean();

        @Override
        public ChatStream<String, String> onData(Consumer<? super String> consumer) {
            dataConsumers.add(consumer);
            return this;
        }

        @Override
        public ChatStream<String, String> onReasoning(Consumer<? super String> consumer) {
            reasoningConsumers.add(consumer);
            return this;
        }

        @Override
        public ChatStream<String, String> onToolCall(Consumer<? super String> consumer) {
            return this;
        }

        @Override
        public ChatStream<String, String> onToolCalls(Consumer<? super List<String>> consumer) {
            return this;
        }

        @Override
        public ChatStream<String, String> onFinish(Consumer<? super ChatAggregateResult<String>> consumer) {
            finishConsumers.add(consumer);
            return this;
        }

        @Override
        public ChatStream<String, String> onComplete(Runnable consumer) {
            completeConsumers.add(consumer);
            return this;
        }

        @Override
        public ChatStream<String, String> onError(Consumer<? super Throwable> consumer) {
            return this;
        }

        @Override
        public ChatStream<String, String> start() {
            if (started.compareAndSet(false, true)) {
                Thread.ofVirtual().start(this::run);
            }
            return this;
        }

        private void run() {
            dataConsumers.forEach(consumer -> consumer.accept("hello"));
            reasoningConsumers.forEach(consumer -> consumer.accept("think"));
            ChatAggregateResult<String> aggregate = new ChatAggregateResult<>(
                    "hello", "think", List.of(),
                    new ChatAggregateResult.TokenUsage(1, 2, 3), true);
            finishConsumers.forEach(consumer -> consumer.accept(aggregate));
            completeConsumers.forEach(Runnable::run);
            future.complete(null);
        }

        @Override
        public CompletableFuture<Void> future() {
            return future;
        }

        @Override
        public CompletableFuture<List<String>> collect() {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        @Override
        public CompletableFuture<ChatAggregateResult<String>> result() {
            return CompletableFuture.completedFuture(new ChatAggregateResult<>(
                    "hello", "think", List.of(),
                    new ChatAggregateResult.TokenUsage(1, 2, 3), true));
        }

        @Override
        public void cancel() {
        }
    }
}
