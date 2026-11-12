package com.taixu.tiangong.core.llm.routing;

import com.taixu.tiangong.common.agent.call.AgentCallContext;
import com.taixu.tiangong.common.type.impl.Openai;
import com.taixu.tiangong.llm.api.Api;
import com.taixu.tiangong.llm.api.request.chat.ChatRequest;
import com.taixu.tiangong.llm.api.response.chat.chunk.ChatChunkResponse;
import com.taixu.tiangong.llm.client.LlmClient;
import com.taixu.tiangong.llm.event.result.ChatResult;
import com.taixu.tiangong.llm.event.stream.ChatStream;
import com.taixu.tiangong.llm.message.HumanMessage;
import com.taixu.tiangong.llm.provider.OpenaiProvider;
import com.taixu.tiangong.protocol.event.AgentEvent;
import com.taixu.tiangong.protocol.event.AgentEventJournal;
import com.taixu.tiangong.protocol.event.AgentEventScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingModelRouteExecutorTest {

    private static final AgentCallContext CONTEXT = new AgentCallContext(
            "u-1", "tenant-1", Set.of(), "trace", Map.of("runId", "run-1", "node", "report"));

    @Test
    void fallsBackWhenCandidateFailsBeforeVisibleEvent() {
        FakeClient flash = new FakeClient(FakeStream.Behavior.ERROR_BEFORE_CONTENT);
        FakeClient pro = new FakeClient(FakeStream.Behavior.SUCCESS);
        AgentEventJournal real = new AgentEventJournal("run-1", "turn-1");
        AgentEventScope.Binding binding = new AgentEventScope.Binding(real, "run-1", "turn-1");

        RoutedStreamResult result = AgentEventScope.callWith(binding,
                () -> AgentCallContext.callWith(CONTEXT, () -> executor(flash, pro).stream(
                        "report", List.of(new HumanMessage("hi")))));

        assertEquals("pro", result.model());
        assertEquals("正文", result.content());
        assertEquals(2, result.attempts().size());
        assertEquals(RoutedAttempt.Status.ERROR, result.attempts().getFirst().status());
        assertEquals(List.of("正文"),
                real.all().stream()
                        .filter(e -> e.event() instanceof AgentEvent.MessageDelta)
                        .map(e -> ((AgentEvent.MessageDelta) e.event()).delta())
                        .toList());
        assertTrue(real.all().stream().noneMatch(e -> e.event() instanceof AgentEvent.Error));
    }

    @Test
    void fallsBackWhenCandidateIsEmptyBeforeAnyVisibleEvent() {
        FakeClient flash = new FakeClient(FakeStream.Behavior.EMPTY);
        FakeClient pro = new FakeClient(FakeStream.Behavior.SUCCESS);
        AgentEventJournal real = new AgentEventJournal("run-1", "turn-1");
        AgentEventScope.Binding binding = new AgentEventScope.Binding(real, "run-1", "turn-1");

        RoutedStreamResult result = AgentEventScope.callWith(binding,
                () -> AgentCallContext.callWith(CONTEXT, () -> executor(flash, pro).stream(
                        "report", List.of(new HumanMessage("hi")))));

        assertEquals("pro", result.model());
        assertEquals(RoutedAttempt.Status.EMPTY, result.attempts().getFirst().status());
        assertEquals(1, real.all().stream()
                .filter(e -> e.event() instanceof AgentEvent.MessageDelta).count());
    }

    @Test
    void doesNotFallbackAfterVisibleContent() {
        FakeClient flash = new FakeClient(FakeStream.Behavior.CONTENT_THEN_ERROR);
        FakeClient pro = new FakeClient(FakeStream.Behavior.SUCCESS);
        AgentEventJournal real = new AgentEventJournal("run-1", "turn-1");
        AgentEventScope.Binding binding = new AgentEventScope.Binding(real, "run-1", "turn-1");

        assertThrows(RoutingException.class, () -> AgentEventScope.callWith(binding,
                () -> AgentCallContext.callWith(CONTEXT, () -> executor(flash, pro).stream(
                        "report", List.of(new HumanMessage("hi"))))));

        assertEquals(0, pro.calls.get(), "可见输出后不应再切换候选");
        assertTrue(real.all().stream().anyMatch(e -> e.event() instanceof AgentEvent.MessageDelta));
        assertTrue(real.all().stream().anyMatch(e -> e.event() instanceof AgentEvent.Error));
        assertTrue(real.all().getLast().event() instanceof AgentEvent.Done done
                && "failed".equals(done.status()));
    }

    @Test
    void doesNotFallbackAfterVisibleReasoningEvenIfAnswerEmpty() {
        FakeClient flash = new FakeClient(FakeStream.Behavior.REASONING_ONLY);
        FakeClient pro = new FakeClient(FakeStream.Behavior.SUCCESS);
        AgentEventJournal real = new AgentEventJournal("run-1", "turn-1");
        AgentEventScope.Binding binding = new AgentEventScope.Binding(real, "run-1", "turn-1");

        assertThrows(RoutingException.class, () -> AgentEventScope.callWith(binding,
                () -> AgentCallContext.callWith(CONTEXT, () -> executor(flash, pro).stream(
                        "report", List.of(new HumanMessage("hi"))))));
        assertEquals(0, pro.calls.get());
        assertTrue(real.all().stream().anyMatch(e -> e.event() instanceof AgentEvent.ReasoningDelta));
        assertTrue(real.all().getLast().event() instanceof AgentEvent.Done done
                && "failed".equals(done.status()));
    }

    @Test
    void allCandidatesEmptyEndsWithFailedEvents() {
        FakeClient flash = new FakeClient(FakeStream.Behavior.EMPTY);
        FakeClient pro = new FakeClient(FakeStream.Behavior.EMPTY);
        AgentEventJournal real = new AgentEventJournal("run-1", "turn-1");
        AgentEventScope.Binding binding = new AgentEventScope.Binding(real, "run-1", "turn-1");

        assertThrows(RoutingException.class, () -> AgentEventScope.callWith(binding,
                () -> AgentCallContext.callWith(CONTEXT, () -> executor(flash, pro).stream(
                        "report", List.of(new HumanMessage("hi"))))));

        assertTrue(real.all().stream().anyMatch(e -> e.event() instanceof AgentEvent.Error));
        assertTrue(real.all().getLast().event() instanceof AgentEvent.Done done
                && "failed".equals(done.status()));
    }

    private static StreamingModelRouteExecutor executor(FakeClient flash, FakeClient pro) {
        ModelRouter router = StaticModelRouter.builder()
                .route(ModelRoute.of("report", "flash", "pro"))
                .build();
        return new StreamingModelRouteExecutor(router,
                Map.of("flash", flash, "pro", pro),
                PricingTable.builder().defaultPricing(new ModelPricing(1, 1, "USD")).build(),
                new InMemoryCostLedger(),
                BudgetGuard.NOOP,
                (name, messages, route) -> ChatRequest.builder()
                        .model(name).messages(messages).stream(true).build(),
                0);
    }

    static final class FakeClient implements LlmClient<Openai, OpenaiProvider> {

        private final FakeStream.Behavior behavior;
        private final AtomicInteger calls = new AtomicInteger();

        FakeClient(FakeStream.Behavior behavior) {
            this.behavior = behavior;
        }

        @Override
        public OpenaiProvider getProvider() {
            return null;
        }

        @Override
        public Api getApi() {
            return null;
        }

        @Override
        public ChatResult<com.taixu.tiangong.llm.api.response.chat.OpenaiChatResponse> chat(ChatRequest chatRequest) {
            return null;
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public ChatStream<? extends ChatChunkResponse<? extends Openai>, ?> stream(ChatRequest request) {
            calls.incrementAndGet();
            return (ChatStream) new FakeStream(AgentEventScope.capture(), behavior);
        }
    }

    static final class FakeStream implements ChatStream<Object, Object> {

        enum Behavior {
            SUCCESS, EMPTY, ERROR_BEFORE_CONTENT, CONTENT_THEN_ERROR, REASONING_ONLY
        }

        private final AgentEventScope.Binding binding;
        private final Behavior behavior;
        private final CompletableFuture<Void> future = new CompletableFuture<>();
        private final CompletableFuture<ChatAggregateResult<Object>> resultFuture = new CompletableFuture<>();

        FakeStream(AgentEventScope.Binding binding, Behavior behavior) {
            this.binding = binding;
            this.behavior = behavior;
        }

        @Override
        public ChatStream<Object, Object> onData(Consumer<? super Object> consumer) {
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
        public ChatStream<Object, Object> onComplete(Runnable consumer) {
            return this;
        }

        @Override
        public ChatStream<Object, Object> onError(Consumer<? super Throwable> consumer) {
            return this;
        }

        @Override
        public ChatStream<Object, Object> start() {
            if (behavior == Behavior.REASONING_ONLY) {
                emit(new AgentEvent.ReasoningDelta("think"));
                complete("");
                return this;
            }
            if (behavior == Behavior.ERROR_BEFORE_CONTENT) {
                fail(new IllegalStateException("upstream failed"));
                return this;
            }
            if (behavior == Behavior.EMPTY) {
                complete("");
                return this;
            }
            emit(new AgentEvent.MessageDelta("正文"));
            if (behavior == Behavior.CONTENT_THEN_ERROR) {
                fail(new IllegalStateException("failed after content"));
                return this;
            }
            complete("正文");
            return this;
        }

        private void complete(String content) {
            ChatAggregateResult<Object> result = new ChatAggregateResult<>(
                    content, "think", List.of(),
                    new ChatAggregateResult.TokenUsage(1, 1, 2), true);
            emit(new AgentEvent.Usage(1, 1, 2, "fake"));
            emit(new AgentEvent.Done("completed"));
            resultFuture.complete(result);
            future.complete(null);
        }

        private void fail(Throwable error) {
            emit(new AgentEvent.Error("stream_failed", error.getMessage(), false));
            emit(new AgentEvent.Done("failed"));
            future.completeExceptionally(error);
        }

        private void emit(AgentEvent event) {
            if (binding != null) {
                AgentEventScope.emit(binding, event);
            }
            if (event instanceof AgentEvent.Done) {
                // no-op: terminal event is forwarded by Staging
            }
        }

        @Override
        public CompletableFuture<Void> future() {
            return future;
        }

        @Override
        public CompletableFuture<List<Object>> collect() {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<ChatAggregateResult<Object>> result() {
            return resultFuture;
        }

        @Override
        public void cancel() {
        }
    }
}
