package com.taixu.tiangong.core.llm.routing;

import com.taixu.tiangong.common.agent.call.AgentCallContext;
import com.taixu.tiangong.llm.api.request.chat.ChatRequest;
import com.taixu.tiangong.llm.api.response.chat.ChatResponse;
import com.taixu.tiangong.llm.client.LlmClient;
import com.taixu.tiangong.llm.message.Message;
import com.taixu.tiangong.trace.TraceRecorder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 按 {@link ModelRoute} 执行同步对话：候选链路降级 + 空回答重试 + token/成本记账。<br>
 * <br>
 * <h2>为什么执行器只做同步</h2>
 * 流式降级的语义更复杂（已经吐给前端的正文不能撤回；切换模型要重新开一轮流，客户端事件 seq
 * 也要续上）。本类先覆盖「结构化输出 / 报告生成 / 分类」这类同步调用；流式路由留给
 * {@code AgentEventJournal} 之上的一层，不在这里假装已经支持。<br>
 * <br>
 * <h2>空回答</h2>
 * deepseek-flash 实测会返回空正文（有 usage、无内容）。空正文不是成功：直接返回会让上层
 * 把空字符串当报告。这里对同一候选重试 {@code emptyRetries} 次，仍空则降级到下一个候选。
 */
public final class ModelRouteExecutor {

    /**
     * 请求工厂：由调用方决定 clientName 对应哪个 model / options / tools。
     * 默认实现只用 clientName 当 model（适合纯测试/同名配置）。
     */
    @FunctionalInterface
    public interface RequestFactory {
        ChatRequest create(String clientName, List<Message> messages, ModelRoute route);
    }

    private final ModelRouter router;
    private final Map<String, LlmClient<?, ?>> clients;
    private final PricingTable pricing;
    private final CostLedger ledger;
    private final BudgetGuard budgetGuard;
    private final RequestFactory requestFactory;
    private final int emptyRetries;

    public ModelRouteExecutor(ModelRouter router,
                              Map<String, LlmClient<?, ?>> clients,
                              PricingTable pricing,
                              CostLedger ledger) {
        this(router, clients, pricing, ledger, BudgetGuard.NOOP,
                ModelRouteExecutor::defaultRequest, 1);
    }

    public ModelRouteExecutor(ModelRouter router,
                              Map<String, LlmClient<?, ?>> clients,
                              PricingTable pricing,
                              CostLedger ledger,
                              RequestFactory requestFactory,
                              int emptyRetries) {
        this(router, clients, pricing, ledger, BudgetGuard.NOOP, requestFactory, emptyRetries);
    }

    public ModelRouteExecutor(ModelRouter router,
                              Map<String, LlmClient<?, ?>> clients,
                              PricingTable pricing,
                              CostLedger ledger,
                              BudgetGuard budgetGuard,
                              RequestFactory requestFactory,
                              int emptyRetries) {
        if (router == null) {
            throw new IllegalArgumentException("ModelRouter 不能为空");
        }
        this.router = router;
        this.clients = clients == null ? Map.of() : Map.copyOf(clients);
        this.pricing = pricing == null ? PricingTable.builder().build() : pricing;
        this.ledger = ledger == null ? CostLedger.NOOP : ledger;
        this.budgetGuard = budgetGuard == null ? BudgetGuard.NOOP : budgetGuard;
        this.requestFactory = requestFactory == null
                ? ModelRouteExecutor::defaultRequest : requestFactory;
        this.emptyRetries = Math.max(0, emptyRetries);
    }

    /**
     * 执行一次路由对话。
     *
     * @throws RoutingException 所有候选都失败/空回答
     */
    public RoutedResult chat(String task, List<Message> messages) {
        AgentCallContext context = AgentCallContext.current();
        // 预算在选模型之前检查：没有预算就不该开始一次可能付费的调用
        budgetGuard.check(context);
        ModelRoute route = router.route(task, context);
        List<Message> safeMessages = messages == null ? List.of() : List.copyOf(messages);
        List<RoutedAttempt> attempts = new ArrayList<>();

        for (String candidate : route.candidates()) {
            LlmClient<?, ?> client = clients.get(candidate);
            if (client == null) {
                attempts.add(RoutedAttempt.error(candidate, 0, "未注册客户端"));
                continue;
            }
            ModelPricing price = pricing.priceFor(candidate);
            long estimatedMicros = estimateMicros(route, safeMessages, price);
            BudgetGuard.Reservation reservation = budgetGuard.reserve(context, estimatedMicros);
            long actualCostMicros = 0;
            try {
                for (int attempt = 0; attempt <= emptyRetries; attempt++) {
                    long startNanos = System.nanoTime();
                    try {
                        ChatRequest request = requestFactory.create(candidate, safeMessages, route);
                        ChatResponse<?> response = client.chat(request).join();
                        TokenUsage usage = UsageExtractor.extract(response);
                        String content = response == null ? null : response.getContent();
                        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
                        if (content == null || content.isBlank()) {
                            attempts.add(RoutedAttempt.empty(candidate, elapsedMs));
                            TraceRecorder.recordLlm(candidate, safeMessages, "", 0, 0, 0, 0,
                                    price.currency(), elapsedMs, "empty", null);
                            continue;
                        }
                        attempts.add(RoutedAttempt.success(candidate, elapsedMs, usage));
                        long cost = price.costMicros(usage.promptTokens(), usage.completionTokens());
                        recordCost(candidate, usage, cost, price, context);
                        TraceRecorder.recordLlm(candidate, safeMessages, content,
                                usage.promptTokens(), usage.completionTokens(), usage.totalTokens(),
                                cost, price.currency(), elapsedMs, "success", null);
                        actualCostMicros = cost;
                        return new RoutedResult(candidate, response, attempts);
                    } catch (RuntimeException e) {
                        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
                        attempts.add(RoutedAttempt.error(candidate, elapsedMs, e.getMessage()));
                        TraceRecorder.recordLlm(candidate, safeMessages, "", 0, 0, 0, 0,
                                price.currency(), elapsedMs, "error", e.getMessage());
                        break;   // 同候选不因异常重试；直接降级，避免把一次持续故障放大成 N 次
                    }
                }
            } finally {
                // 只在这里 settle 一次：成功带实际成本，失败/空回答带 0；预留必须释放
                reservation.settle(actualCostMicros);
            }
        }
        throw new RoutingException("任务 [" + task + "] 的所有候选模型均失败: " + attempts);
    }

    /**
     * 预留估算：prompt 字符数（保守上界）+ 路由的 maxOutputTokens（未配置按 512）。
     * 这不是账单，只是预算闸门的估计；实际成本仍由 provider usage 结算。
     */
    private static long estimateMicros(ModelRoute route, List<Message> messages, ModelPricing price) {
        long promptChars = messages.stream()
                .mapToLong(message -> message.getContent() == null
                        ? 0 : message.getContent().length())
                .sum();
        long promptTokens = Math.max(64, promptChars);
        long completionTokens = route.maxOutputTokens() > 0 ? route.maxOutputTokens() : 512;
        return price.costMicros(promptTokens, completionTokens);
    }

    private void recordCost(String model, TokenUsage usage, long cost, ModelPricing price,
                            AgentCallContext context) {        if (ledger == CostLedger.NOOP) {
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
                .stream(false)
                .maxReasoningTokens(route.maxReasoningTokens() > 0 ? route.maxReasoningTokens() : null)
                .build();
    }
}
