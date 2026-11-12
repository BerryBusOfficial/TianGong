package com.taixu.tiangong.llm.event.stream;

import com.taixu.tiangong.protocol.event.AgentEventJournal;
import com.taixu.tiangong.protocol.event.AgentEventScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolLoopSupportCancellationTest {

    @Test
    void journalCancelPropagatesToChatStream() {
        AgentEventJournal journal = new AgentEventJournal("run", "turn");
        AgentEventScope.Binding binding = new AgentEventScope.Binding(journal, "run", "turn");
        FakeStream fake = new FakeStream();
        AgentEventScope.runWith(binding, () -> {
            ChatStream<Object, Object> wrapped = ToolLoopSupport.wrap(fake, null, null, null);
            journal.cancel();
            assertTrue(fake.cancelled.get(), "journal.cancel() 应取消上游流");
            assertTrue(wrapped.future().isDone() || fake.cancelled.get());
        });
    }

    private static final class FakeStream implements ChatStream<Object, Object> {

        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final CompletableFuture<Void> future = new CompletableFuture<>();
        private final CompletableFuture<List<Object>> collected = CompletableFuture.completedFuture(List.of());
        private final CompletableFuture<ChatAggregateResult<Object>> result =
                CompletableFuture.completedFuture(new ChatAggregateResult<>(
                        "", "", List.of(), new ChatAggregateResult.TokenUsage(0, 0, 0), true));

        @Override
        public ChatStream<Object, Object> onData(Consumer<? super Object> consumer) {
            return this;
        }

        @Override
        public ChatStream<Object, Object> onComplete(Runnable consumer) {
            return this;
        }

        @Override
        public ChatStream<Object, Object> start() {
            return this;
        }

        @Override
        public ChatStream<Object, Object> onError(Consumer<? super Throwable> consumer) {
            return this;
        }

        @Override
        public ChatStream<Object, Object> onReasoning(Consumer<? super String> consumer) {
            return this;
        }

        @Override
        public ChatStream<Object, Object> onToolCall(Consumer<? super Object> consumer) {
            return this;
        }

        @Override
        public ChatStream<Object, Object> onToolCalls(Consumer<? super List<Object>> consumer) {
            return this;
        }

        @Override
        public ChatStream<Object, Object> onFinish(Consumer<? super ChatAggregateResult<Object>> consumer) {
            return this;
        }

        @Override
        public void cancel() {
            cancelled.set(true);
            future.complete(null);
        }

        @Override
        public CompletableFuture<List<Object>> collect() {
            return collected;
        }

        @Override
        public CompletableFuture<Void> future() {
            return future;
        }

        @Override
        public CompletableFuture<ChatAggregateResult<Object>> result() {
            return result;
        }
    }
}
