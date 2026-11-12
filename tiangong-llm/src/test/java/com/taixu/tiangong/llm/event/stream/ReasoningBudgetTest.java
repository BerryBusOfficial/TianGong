package com.taixu.tiangong.llm.event.stream;

import com.taixu.tiangong.llm.api.request.chat.ChatRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReasoningBudgetTest {

    @Test
    void exceedingBudgetCancelsUpstreamAndFailsStream() {
        FakeStream raw = new FakeStream("12345", "67890");
        ReasoningBudgetChatStream<String, String> stream =
                new ReasoningBudgetChatStream<>(raw, 6);

        stream.start();
        assertThrows(ReasoningBudgetExceededException.class, stream::await);
        assertTrue(raw.cancelled.get());
    }

    @Test
    void underBudgetCompletesNormally() {
        FakeStream raw = new FakeStream("123", "456");
        ReasoningBudgetChatStream<String, String> stream =
                new ReasoningBudgetChatStream<>(raw, 100);
        List<String> reasoning = new ArrayList<>();
        stream.onReasoning(reasoning::add).start().await();

        assertEquals(List.of("123", "456"), reasoning);
        assertTrue(stream.result().join().success());
    }

    @Test
    void toolLoopSupportAppliesRequestBudget() {
        ToolLoopSupport.install(null);
        FakeStream raw = new FakeStream("1234567890");
        ChatRequest request = ChatRequest.builder()
                .model("m")
                .maxReasoningTokens(5)
                .build();
        ChatStream<String, String> stream = ToolLoopSupport.wrap(
                raw, request, null, call -> null, text -> text);

        stream.start();
        assertThrows(ReasoningBudgetExceededException.class, stream::await);
        assertTrue(raw.cancelled.get());
    }

    /** 只实现事件转发所需的最小流；start 同步触发 reasoning + complete。 */
    static final class FakeStream implements ChatStream<String, String> {

        private final List<String> reasoningChunks;
        private final List<Consumer<? super String>> reasoningConsumers = new CopyOnWriteArrayList<>();
        private final List<Runnable> completeConsumers = new CopyOnWriteArrayList<>();
        private final CompletableFuture<Void> future = new CompletableFuture<>();
        private final CompletableFuture<ChatAggregateResult<String>> resultFuture = new CompletableFuture<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final List<String> collected = new ArrayList<>();

        FakeStream(String... reasoningChunks) {
            this.reasoningChunks = List.of(reasoningChunks);
        }

        @Override
        public ChatStream<String, String> onData(Consumer<? super String> consumer) {
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
            for (String chunk : reasoningChunks) {
                if (cancelled.get()) {
                    break;
                }
                reasoningConsumers.forEach(consumer -> consumer.accept(chunk));
            }
            if (!cancelled.get()) {
                ChatAggregateResult<String> result = new ChatAggregateResult<>(
                        "", String.join("", reasoningChunks), List.of(),
                        new ChatAggregateResult.TokenUsage(1, 1, 2), true);
                resultFuture.complete(result);
                completeConsumers.forEach(Runnable::run);
                future.complete(null);
            }
            return this;
        }

        @Override
        public CompletableFuture<Void> future() {
            return future;
        }

        @Override
        public CompletableFuture<List<String>> collect() {
            return CompletableFuture.completedFuture(collected);
        }

        @Override
        public CompletableFuture<ChatAggregateResult<String>> result() {
            return resultFuture;
        }

        @Override
        public void cancel() {
            cancelled.set(true);
        }
    }
}
