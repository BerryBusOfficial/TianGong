package com.taixu.tiangong.core.llm.routing;

import com.taixu.tiangong.common.agent.call.AgentCallContext;
import com.taixu.tiangong.common.type.impl.Openai;
import com.taixu.tiangong.llm.api.Api;
import com.taixu.tiangong.llm.api.request.chat.ChatRequest;
import com.taixu.tiangong.llm.api.response.chat.OpenaiChatResponse;
import com.taixu.tiangong.llm.api.response.chat.chunk.ChatChunkResponse;
import com.taixu.tiangong.llm.client.LlmClient;
import com.taixu.tiangong.llm.event.result.ChatResult;
import com.taixu.tiangong.llm.event.stream.ChatStream;
import com.taixu.tiangong.llm.message.HumanMessage;
import com.taixu.tiangong.llm.provider.OpenaiProvider;
import com.taixu.tiangong.trace.InMemoryTraceStore;
import com.taixu.tiangong.trace.LlmTraceRecord;
import com.taixu.tiangong.trace.TraceRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelRouteExecutorTest {

    private static final ModelPricing PRICING = new ModelPricing(1_000_000, 2_000_000, "USD");

    @AfterEach
    void tearDown() {
        TraceRecorder.reset();
    }

    @Test
    void recordsTraceForEmptyAttemptAndSuccessfulFallback() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        TraceRecorder.install(store);
        ModelRouteExecutor executor = executor(new InMemoryCostLedger(), 0,
                Map.of("flash", new FakeClient("", 0, 0, null),
                        "pro", new FakeClient("ok", 10, 5, null)));
        AgentCallContext context = new AgentCallContext("u-1", "tenant-1", Set.of(), "trace",
                Map.of("runId", "run-1", "node", "report"));

        AgentCallContext.callWith(context,
                () -> executor.chat("report", List.of(new HumanMessage("hi"))));

        List<LlmTraceRecord> traces = store.llmCalls("run-1");
        assertEquals(2, traces.size());
        assertEquals("empty", traces.getFirst().status());
        assertEquals("success", traces.getLast().status());
        assertEquals(64, traces.getLast().promptHash().length());
        assertEquals("report", traces.getLast().node());
        assertEquals(15, traces.getLast().totalTokens());
    }

    @Test
    void fallsBackOnEmptyAnswerAndRecordsCost() {
        FakeClient flash = new FakeClient("", 10, 0, null);
        FakeClient pro = new FakeClient("报告正文", 1000, 500, null);
        InMemoryCostLedger ledger = new InMemoryCostLedger();
        ModelRouteExecutor executor = executor(ledger, 0, Map.of("flash", flash, "pro", pro));

        AgentCallContext context = new AgentCallContext("u-1", "tenant-1", Set.of(), "trace",
                Map.of("runId", "run-1", "node", "report"));
        RoutedResult result = AgentCallContext.callWith(context,
                () -> executor.chat("report", List.of(new HumanMessage("写周报"))));

        assertEquals("pro", result.model());
        assertEquals("报告正文", result.response().getContent());
        assertEquals(2, result.attempts().size());
        assertEquals(RoutedAttempt.Status.EMPTY, result.attempts().getFirst().status());

        assertEquals(1, ledger.entries().size());
        CostEntry entry = ledger.entries().getFirst();
        assertEquals("pro", entry.model());
        assertEquals("run-1", entry.runId());
        assertEquals("report", entry.node());
        assertEquals("u-1", entry.userId());
        assertEquals(1500, entry.totalTokens());
        assertEquals(2000, entry.costMicros());
    }

    @Test
    void fallsBackOnTransportError() {
        FakeClient flash = new FakeClient(null, 0, 0, new IllegalStateException("rate limited"));
        FakeClient pro = new FakeClient("ok", 10, 5, null);
        ModelRouteExecutor executor = executor(new InMemoryCostLedger(), 0,
                Map.of("flash", flash, "pro", pro));

        RoutedResult result = executor.chat("report", List.of(new HumanMessage("hi")));
        assertEquals("pro", result.model());
        assertEquals(RoutedAttempt.Status.ERROR, result.attempts().getFirst().status());
        assertTrue(result.attempts().getFirst().detail().contains("rate limited"));
    }

    @Test
    void allCandidatesFailingThrowsWithAttemptChain() {
        FakeClient flash = new FakeClient(null, 0, 0, new IllegalStateException("boom"));
        FakeClient pro = new FakeClient("", 0, 0, null);
        ModelRouteExecutor executor = executor(new InMemoryCostLedger(), 0,
                Map.of("flash", flash, "pro", pro));

        RoutingException error = assertThrows(RoutingException.class,
                () -> executor.chat("report", List.of(new HumanMessage("hi"))));
        assertTrue(error.getMessage().contains("flash"));
        assertTrue(error.getMessage().contains("pro"));
    }

    @Test
    void unknownTaskFallsBackToDefaultRoute() {
        StaticModelRouter router = StaticModelRouter.builder()
                .route(ModelRoute.of("report", "flash"))
                .defaultRoute("pro")
                .build();
        assertEquals("flash", router.route("report", null).primary());
        assertEquals("pro", router.route("unknown", null).primary());
    }

    @Test
    void missingClientIsRecordedAsErrorAndFallsThrough() {
        ModelRouteExecutor executor = executor(new InMemoryCostLedger(), 0,
                Map.of("pro", new FakeClient("ok", 1, 1, null)));
        RoutedResult result = executor.chat("report", List.of(new HumanMessage("hi")));
        assertEquals("pro", result.model());
        assertEquals(RoutedAttempt.Status.ERROR, result.attempts().getFirst().status());
        assertTrue(result.attempts().getFirst().detail().contains("未注册客户端"));
    }

    @Test
    void budgetGuardBlocksBeforeCallingClient() {
        InMemoryCostLedger ledger = new InMemoryCostLedger();
        ledger.record(new CostEntry("run-0", "report", "u-1", "tenant-1", "flash",
                10, 10, 20, 100, "USD", java.time.Instant.now()));
        TenantBudgetGuard guard = TenantBudgetGuard.builder(ledger)
                .limit("tenant-1", 50)
                .build();
        FakeClient flash = new FakeClient("ok", 1, 1, null);
        ModelRouter router = StaticModelRouter.builder()
                .route(ModelRoute.of("report", "flash"))
                .build();
        ModelRouteExecutor executor = new ModelRouteExecutor(router, Map.of("flash", flash),
                PricingTable.builder().defaultPricing(PRICING).build(), ledger, guard,
                (name, messages, route) -> ChatRequest.builder()
                        .model(name).messages(messages).stream(false).build(), 0);

        AgentCallContext context = new AgentCallContext("u-1", "tenant-1", Set.of(), "trace");
        assertThrows(BudgetExceededException.class,
                () -> AgentCallContext.callWith(context,
                        () -> executor.chat("report", List.of(new HumanMessage("hi")))));
        assertEquals(0, flash.calls);
    }

    @Test
    void budgetReservationBlocksBeforeCallingClient() {
        InMemoryCostLedger ledger = new InMemoryCostLedger();
        TenantBudgetGuard guard = TenantBudgetGuard.builder(ledger)
                .limit("tenant-1", 500)
                .build();
        FakeClient flash = new FakeClient("ok", 1, 1, null);
        ModelRoute route = new ModelRoute("report", List.of("flash"), 0, 1000);
        ModelRouteExecutor executor = new ModelRouteExecutor(
                StaticModelRouter.builder().route(route).build(),
                Map.of("flash", flash),
                PricingTable.builder().defaultPricing(new ModelPricing(1_000_000, 1_000_000, "USD")).build(),
                ledger, guard,
                (name, messages, r) -> ChatRequest.builder()
                        .model(name).messages(messages).stream(false).build(), 0);

        AgentCallContext context = new AgentCallContext("u-1", "tenant-1", Set.of(), "trace");
        assertThrows(BudgetExceededException.class,
                () -> AgentCallContext.callWith(context,
                        () -> executor.chat("report", List.of(new HumanMessage("hi")))));
        assertEquals(0, flash.calls);
        assertEquals(0, ledger.reservedMicros("tenant-1"));
    }

    @Test
    void reservationIsReleasedAfterSuccess() {
        InMemoryCostLedger ledger = new InMemoryCostLedger();
        TenantBudgetGuard guard = TenantBudgetGuard.builder(ledger)
                .limit("tenant-1", 5000)
                .build();
        ModelRoute route = new ModelRoute("report", List.of("flash"), 0, 1000);
        ModelRouteExecutor executor = new ModelRouteExecutor(
                StaticModelRouter.builder().route(route).build(),
                Map.of("flash", new FakeClient("ok", 1, 1, null)),
                PricingTable.builder().defaultPricing(new ModelPricing(1, 1, "USD")).build(),
                ledger, guard,
                (name, messages, r) -> ChatRequest.builder()
                        .model(name).messages(messages).stream(false).build(), 0);

        AgentCallContext context = new AgentCallContext("u-1", "tenant-1", Set.of(), "trace");
        AgentCallContext.callWith(context,
                () -> executor.chat("report", List.of(new HumanMessage("hi"))));

        assertEquals(0, ledger.reservedMicros("tenant-1"));
        assertEquals(1, ledger.entries().size());
    }

    @Test
    void tenantGuardReservationIsAtomicAndReleasable() {
        InMemoryCostLedger ledger = new InMemoryCostLedger();
        TenantBudgetGuard guard = TenantBudgetGuard.builder(ledger)
                .limit("tenant-1", 1000)
                .build();
        AgentCallContext context = new AgentCallContext("u-1", "tenant-1", Set.of(), "trace");

        BudgetGuard.Reservation first = guard.reserve(context, 600);
        assertThrows(BudgetExceededException.class, () -> guard.reserve(context, 600));
        first.settle(0);
        BudgetGuard.Reservation second = guard.reserve(context, 600);
        second.settle(0);
        assertEquals(0, ledger.reservedMicros("tenant-1"));
    }

    private static ModelRouteExecutor executor(InMemoryCostLedger ledger, int emptyRetries,
                                               Map<String, LlmClient<?, ?>> clients) {
        ModelRouter router = StaticModelRouter.builder()
                .route(ModelRoute.of("report", "flash", "pro"))
                .build();
        PricingTable pricing = PricingTable.builder()
                .defaultPricing(PRICING)
                .build();
        return new ModelRouteExecutor(router, clients, pricing, ledger,
                (name, messages, route) -> ChatRequest.builder()
                        .model(name)
                        .messages(messages)
                        .stream(false)
                        .build(),
                emptyRetries);
    }

    static final class FakeClient implements LlmClient<Openai, OpenaiProvider> {

        private final String content;
        private final long promptTokens;
        private final long completionTokens;
        private final RuntimeException error;
        private int calls;

        FakeClient(String content, long promptTokens, long completionTokens, RuntimeException error) {
            this.content = content;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.error = error;
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
        public ChatResult<OpenaiChatResponse> chat(ChatRequest chatRequest) {
            calls++;
            if (error != null) {
                throw error;
            }
            OpenaiChatResponse response = new OpenaiChatResponse(
                    "id", "chat.completion", 0, "fake",
                    List.of(new OpenaiChatResponse.Choice(0,
                            new OpenaiChatResponse.ResponseMessage("assistant", content,
                                    null, null, null, null), null, "stop")),
                    null,
                    new OpenaiChatResponse.Usage(promptTokens, completionTokens,
                            promptTokens + completionTokens, null, null),
                    null);
            return new ChatResult<>() {
                @Override
                public ChatResult<OpenaiChatResponse> onData(Consumer<? super OpenaiChatResponse> consumer) {
                    consumer.accept(response);
                    return this;
                }

                @Override
                public ChatResult<OpenaiChatResponse> onComplete(Runnable consumer) {
                    consumer.run();
                    return this;
                }

                @Override
                public ChatResult<OpenaiChatResponse> onError(Consumer<? super Throwable> consumer) {
                    return this;
                }

                @Override
                public OpenaiChatResponse join() {
                    return response;
                }

                @Override
                public void cancel() {
                }
            };
        }

        @Override
        public ChatStream<? extends ChatChunkResponse<? extends Openai>, ?> stream(ChatRequest request) {
            return null;
        }
    }
}
