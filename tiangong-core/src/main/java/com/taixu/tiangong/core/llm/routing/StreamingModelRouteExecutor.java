package com.taixu.tiangong.core.llm.routing;

import com.taixu.tiangong.common.agent.call.AgentCallContext;
import com.taixu.tiangong.llm.api.request.chat.ChatRequest;
import com.taixu.tiangong.llm.client.LlmClient;
import com.taixu.tiangong.llm.event.stream.ChatStream;
import com.taixu.tiangong.llm.event.stream.ToolCall;
import com.taixu.tiangong.llm.message.Message;
import com.taixu.tiangong.protocol.event.AgentEvent;
import com.taixu.tiangong.protocol.event.AgentEventEnvelope;
import com.taixu.tiangong.protocol.event.AgentEventJournal;
import com.taixu.tiangong.protocol.event.AgentEventScope;
import com.taixu.tiangong.trace.TraceRecorder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流式模型路由：候选链降级 + 空回答重试 + 推理预算/成本。<br>
 * <br>
 * <h2>降级边界：首个“可见事件”之前才允许切换</h2>
 * 每个候选先写入一个<b>候选事件日志</b>，在出现正文/思考/工具/引用/产物/结构化事件之前，
 * 不向真实事件流发任何东西；此时失败或空回答可以安全降级，客户端看不到被丢弃的候选。
 * 一旦出现可见事件，候选被锁定：后续失败只转发 error/done，不再切模型 —— 否则客户端会
 * 收到两段正文，或工具已执行却被“回退”到另一个模型。<br>
 * <br>
 * <b>为什么从事件层而不是 ChatStream 返回值做</b>：流式失败经常发生在开始吐字之后；
 * 只看返回值等于在客户端已经看到内容后才决定重试，语义已经破坏。
 */
public final class StreamingModelRouteExecutor {

    private final ModelRouter router;
    private final Map<String, LlmClient<?, ?>> clients;
    private final ModelRouteExecutor.RequestFactory requestFactory;
    private final PricingTable pricing;
    private final CostLedger ledger;
    private final BudgetGuard budgetGuard;
    private final int emptyRetries;

    public StreamingModelRouteExecutor(ModelRouter router,
                                       Map<String, LlmClient<?, ?>> clients,
                                       PricingTable pricing,
                                       CostLedger ledger) {
        this(router, clients, pricing, ledger, BudgetGuard.NOOP,
                StreamingModelRouteExecutor::defaultRequest, 1);
    }

    public StreamingModelRouteExecutor(ModelRouter router,
                                       Map<String, LlmClient<?, ?>> clients,
                                       PricingTable pricing,
                                       CostLedger ledger,
                                       BudgetGuard budgetGuard,
                                       ModelRouteExecutor.RequestFactory requestFactory,
                                       int emptyRetries) {
        this.router = router;
        this.clients = clients == null ? Map.of() : Map.copyOf(clients);
        this.pricing = pricing == null ? PricingTable.builder().build() : pricing;
        this.ledger = ledger == null ? CostLedger.NOOP : ledger;
        this.budgetGuard = budgetGuard == null ? BudgetGuard.NOOP : budgetGuard;
        this.requestFactory = requestFactory == null
                ? StreamingModelRouteExecutor::defaultRequest : requestFactory;
        this.emptyRetries = Math.max(0, emptyRetries);
    }

    public RoutedStreamResult stream(String task, List<Message> messages) {
        AgentCallContext context = AgentCallContext.current();
        budgetGuard.check(context);
        ModelRoute route = router.route(task, context);
        List<Message> safeMessages = messages == null ? List.of() : List.copyOf(messages);
        AgentEventScope.Binding real = AgentEventScope.capture();
        String runId = real == null ? "route-" + UUID.randomUUID() : real.runId();
        String turnId = real == null ? "" : real.turnId();

        List<RoutedAttempt> attempts = new ArrayList<>();
        for (String candidate : route.candidates()) {
            LlmClient<?, ?> client = clients.get(candidate);
            if (client == null) {
                attempts.add(RoutedAttempt.error(candidate, 0, "未注册客户端"));
                continue;
            }
            ModelPricing price = pricing.priceFor(candidate);
            long estimated = ModelRouteExecutor.estimateMicros(route, safeMessages, price);
            BudgetGuard.Reservation reservation = budgetGuard.reserve(context, estimated);
            long actualCostMicros = 0;
            try {
                for (int emptyAttempt = 0; emptyAttempt <= emptyRetries; emptyAttempt++) {
                    long startNanos = System.nanoTime();
                    Staging staging = new Staging(real);
                    AgentEventJournal candidateJournal = new AgentEventJournal(runId, turnId);
                    AgentEventScope.Binding candidateBinding =
                            new AgentEventScope.Binding(candidateJournal, runId, turnId);

                    @SuppressWarnings("rawtypes")
                    ChatStream stream;
                    try {
                        stream = AgentEventScope.callWith(candidateBinding, () -> {
                            ChatRequest request = requestFactory.create(candidate, safeMessages, route);
                            return client.stream(request);
                        });
                    } catch (RuntimeException e) {
                        // 建立流即失败：还没有任何事件，直接降级
                        attempts.add(RoutedAttempt.error(candidate, elapsed(startNanos), e.getMessage()));
                        TraceRecorder.recordLlm(candidate, safeMessages, "", 0, 0, 0, 0,
                                price.currency(), elapsed(startNanos), "error", e.getMessage());
                        break;
                    }
                    if (stream == null) {
                        attempts.add(RoutedAttempt.error(candidate, elapsed(startNanos), "stream 为 null"));
                        break;
                    }

                    Throwable failure = null;
                    try (AgentEventJournal.Subscription ignored =
                                 candidateJournal.subscribe(staging::onEvent, 0)) {
                        try {
                            stream.start().await();
                        } catch (RuntimeException e) {
                            failure = e;
                        }
                    }
                    long elapsedMs = elapsed(startNanos);

                    if (failure != null) {
                        attempts.add(RoutedAttempt.error(candidate, elapsedMs, failure.getMessage()));
                        TraceRecorder.recordLlm(candidate, safeMessages, "", 0, 0, 0, 0,
                                price.currency(), elapsedMs, "error", failure.getMessage());
                        if (staging.promoted()) {
                            staging.fail(failure.getMessage());
                            throw new RoutingException(
                                    "候选 [" + candidate + "] 在输出后失败，已锁定不再降级: "
                                            + failure.getMessage(), failure);
                        }
                        break;   // 未输出任何可见事件：允许降级
                    }

                    @SuppressWarnings("rawtypes")
                    ChatStream.ChatAggregateResult aggregate =
                            (ChatStream.ChatAggregateResult) stream.result().join();
                    String content = aggregate == null ? "" : aggregate.content();
                    TokenUsage usage = toUsage(aggregate);
                    List<ToolCall> toolCalls = staging.toolCalls();

                    if (!staging.promoted()) {
                        if (content.isBlank() && toolCalls.isEmpty()) {
                            attempts.add(RoutedAttempt.empty(candidate, elapsedMs));
                            TraceRecorder.recordLlm(candidate, safeMessages, "", 0, 0, 0, 0,
                                    price.currency(), elapsedMs, "empty", null);
                            continue;   // 同一候选重试，仍空则降级
                        }
                        // 没有事件桥（或 provider 没发 delta），但有正文：补发事件后锁定
                        staging.promote(content, usage);
                    }
                    if (content.isBlank() && toolCalls.isEmpty()) {
                        // 只有思考/用量等可见事件，没有最终回答：已锁定，不能再降级
                        staging.fail("模型在可见输出后未给出最终回答");
                        attempts.add(RoutedAttempt.empty(candidate, elapsedMs));
                        TraceRecorder.recordLlm(candidate, safeMessages, "", 0, 0, 0, 0,
                                price.currency(), elapsedMs, "empty", null);
                        throw new RoutingException(
                                "候选 [" + candidate + "] 在可见输出后空回答，已锁定不再降级");
                    }

                    if (!staging.complete(usage)) {
                        attempts.add(RoutedAttempt.error(candidate, elapsedMs, "上游结束状态失败"));
                        TraceRecorder.recordLlm(candidate, safeMessages, content,
                                usage.promptTokens(), usage.completionTokens(), usage.totalTokens(),
                                0, price.currency(), elapsedMs, "error", "上游结束状态失败");
                        throw new RoutingException(
                                "候选 [" + candidate + "] 以 failed 结束，已输出内容，不再降级");
                    }
                    long cost = price.costMicros(usage.promptTokens(), usage.completionTokens());
                    actualCostMicros = cost;
                    recordCost(candidate, usage, cost, price, context);
                    TraceRecorder.recordLlm(candidate, safeMessages, content,
                            usage.promptTokens(), usage.completionTokens(), usage.totalTokens(),
                            cost, price.currency(), elapsedMs, "success", null);
                    attempts.add(RoutedAttempt.success(candidate, elapsedMs, usage));
                    return new RoutedStreamResult(candidate, content, toolCalls, usage, attempts);
                }
            } finally {
                reservation.settle(actualCostMicros);
            }
        }

        String detail = "所有候选模型均失败: " + attempts;
        AgentEventScope.emit(real, new AgentEvent.Error("all_candidates_failed", detail, false));
        AgentEventScope.emit(real, new AgentEvent.Done("failed"));
        throw new RoutingException(detail);
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    @SuppressWarnings("rawtypes")
    private static TokenUsage toUsage(ChatStream.ChatAggregateResult aggregate) {
        if (aggregate == null || aggregate.usage() == null) {
            return TokenUsage.ZERO;
        }
        ChatStream.ChatAggregateResult.TokenUsage usage = aggregate.usage();
        return new TokenUsage(usage.promptTokens(), usage.completionTokens(), usage.totalTokens());
    }

    private void recordCost(String model, TokenUsage usage, long cost, ModelPricing price,
                            AgentCallContext context) {
        if (ledger == CostLedger.NOOP) {
            return;
        }
        ledger.record(new CostEntry(
                attr(context, "runId", context == null ? null : context.traceId()),
                attr(context, "node", null),
                context == null ? null : context.userId(),
                context == null ? null : context.tenantId(),
                model,
                usage.promptTokens(),
                usage.completionTokens(),
                usage.totalTokens(),
                cost,
                price.currency(),
                Instant.now()));
    }

    private static String attr(AgentCallContext context, String key, String fallback) {
        if (context == null) {
            return fallback;
        }
        Object value = context.attributes().get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static ChatRequest defaultRequest(String clientName, List<Message> messages, ModelRoute route) {
        return ChatRequest.builder()
                .model(clientName)
                .messages(messages)
                .stream(true)
                .maxReasoningTokens(route.maxReasoningTokens() > 0 ? route.maxReasoningTokens() : null)
                .build();
    }

    /**
     * 候选事件舞台：可见事件之前只暂存；可见之后转发到真实事件流。<br>
     * <b>终止事件先缓冲</b>：上游可能在“空回答”时先发 done=completed，路由层需要先判断
     * 正文/工具是否为空；若直接转发 done，真实日志就终止了，后面再也补不了 error。
     */
    private static final class Staging {

        private final AgentEventScope.Binding real;
        private final List<AgentEvent> staged = new ArrayList<>();
        private final List<ToolCall> toolCalls = new ArrayList<>();
        private final AtomicBoolean promoted = new AtomicBoolean();
        private final AtomicBoolean done = new AtomicBoolean();
        private final AtomicBoolean usageEmitted = new AtomicBoolean();
        private boolean errorForwarded;
        private String pendingDoneStatus;

        private Staging(AgentEventScope.Binding real) {
            this.real = real;
        }

        synchronized void onEvent(AgentEventEnvelope envelope) {
            AgentEvent event = envelope.event();
            if (event instanceof AgentEvent.ToolCall call) {
                toolCalls.add(new ToolCall(call.callId(), call.name(), call.arguments()));
            }
            if (promoted.get()) {
                accept(event);
                return;
            }
            staged.add(event);
            if (visible(event)) {
                promoted.set(true);
                for (AgentEvent pending : staged) {
                    accept(pending);
                }
                staged.clear();
            }
        }

        private void accept(AgentEvent event) {
            if (event instanceof AgentEvent.Done doneEvent) {
                // 先不转发：等路由层确认正文/工具非空后再决定 completed 还是 failed
                pendingDoneStatus = doneEvent.status();
                return;
            }
            if (event instanceof AgentEvent.Error) {
                errorForwarded = true;
                forward(event);
                return;
            }
            forward(event);
        }

        synchronized boolean promoted() {
            return promoted.get();
        }

        synchronized List<ToolCall> toolCalls() {
            return List.copyOf(toolCalls);
        }

        /** 没有事件桥但有正文时补发：正文 → usage → done */
        synchronized void promote(String content, TokenUsage usage) {
            promoted.set(true);
            if (content != null && !content.isBlank()) {
                forward(new AgentEvent.MessageDelta(content));
            }
            emitUsage(usage);
            finish("completed");
            staged.clear();
        }

        /** 候选已输出后成功收尾；返回 false 表示上游其实以 failed 结束 */
        synchronized boolean complete(TokenUsage usage) {
            if (pendingDoneStatus != null && !"completed".equals(pendingDoneStatus)) {
                fail("上游结束状态: " + pendingDoneStatus);
                return false;
            }
            emitUsage(usage);
            finish("completed");
            return true;
        }

        synchronized void fail(String message) {
            if (done.get()) {
                return;
            }
            if (!errorForwarded) {
                forward(new AgentEvent.Error("stream_failed",
                        message == null ? "流式调用失败" : message, false));
            }
            finish("failed");
        }

        private void emitUsage(TokenUsage usage) {
            if (usage == null || usage.totalTokens() <= 0 || !usageEmitted.compareAndSet(false, true)) {
                return;
            }
            forward(new AgentEvent.Usage(usage.promptTokens(), usage.completionTokens(),
                    usage.totalTokens(), null));
        }

        private void finish(String status) {
            if (done.compareAndSet(false, true)) {
                forward(new AgentEvent.Done(status));
            }
        }

        private void forward(AgentEvent event) {
            if (real != null) {
                AgentEventScope.emit(real, event);
            }
            if (event instanceof AgentEvent.Usage) {
                usageEmitted.set(true);
            }
        }

        private static boolean visible(AgentEvent event) {
            if (event instanceof AgentEvent.MessageDelta delta) {
                return delta.delta() != null && !delta.delta().isBlank();
            }
            if (event instanceof AgentEvent.ReasoningDelta delta) {
                return delta.delta() != null && !delta.delta().isBlank();
            }
            return event instanceof AgentEvent.ToolCall
                    || event instanceof AgentEvent.ToolResult
                    || event instanceof AgentEvent.Citation
                    || event instanceof AgentEvent.Artifact
                    || event instanceof AgentEvent.Structured;
        }
    }
}
