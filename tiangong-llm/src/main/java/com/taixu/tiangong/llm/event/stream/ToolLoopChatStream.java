package com.taixu.tiangong.llm.event.stream;

import lombok.extern.slf4j.Slf4j;
import com.taixu.tiangong.common.agent.call.AgentCallContext;
import com.taixu.tiangong.common.thread.VirtualThreads;
import com.taixu.tiangong.llm.api.response.chat.chunk.ChatChunkResponse;
import com.taixu.tiangong.protocol.event.AgentEvent;
import com.taixu.tiangong.protocol.event.AgentEventScope;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 工具循环流：把一轮「带工具的流式对话」扩展成<b>多轮流</b>，直到模型不再请求工具。<br>
 * <br>
 * <h2>语义</h2>
 * <ul>
 *     <li>实时回调（onData / onReasoning）<b>按轮透出</b>：模型边说话边调工具时，
 *         工具调用前的正文也会推给调用方（控制台上看得到「它在干活」）；</li>
 *     <li>工具回调（onToolCall / onToolCalls）在<b>整个循环结束时</b>一次性回调
 *         全部轮次的调用（与单轮流「流结束时统一回调完整工具调用」的语义对齐）；</li>
 *     <li>onComplete 只触发一次 —— 在最后一轮结束之后；</li>
 *     <li>{@code result()} 聚合<b>最后一轮</b>的正文/思考/用量（最终答复），
 *         但 toolCalls 字段包含全部轮次；{@code collect()} 收集全部轮次的原始 chunk。</li>
 * </ul>
 * <br>
 * <h2>驱动方式</h2>
 * 循环在本流自己的虚拟线程里逐轮驱动裸流：读完一轮 → 有工具调用就交给
 * {@link ToolLoop} 执行并要下一轮流 → 继续读。续发时如果客户端又包了一层本类
 * （工厂是全局的，{@code client.stream()} 一定会再包），会先拆掉包装直接驱动裸流，
 * 保证「一个循环流、一条虚拟线程」。
 */
@Slf4j
public class ToolLoopChatStream<T, TCall> implements ChatStream<T, TCall> {

    /**
     * 循环轮数硬上限：模型若陷入「反复调工具」的退化行为，靠它兜底止损<br>
     * （与 AgentLoopExecutor 的节点环检测同理 —— 区别是这里的环由模型产生，无法静态发现）。
     */
    private static final int MAX_ROUNDS = 12;

    private final ChatStream<T, TCall> first;
    private final ToolLoop loop;
    private final Function<TCall, ToolCall> extractor;
    private final Function<T, String> deltaExtractor;
    private final AgentEventScope.Binding eventBinding;
    private final AgentCallContext.Binding callBinding;
    private final AtomicBoolean terminalEmitted = new AtomicBoolean();

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

    private final List<T> allChunks = new ArrayList<>();
    private final List<TCall> allToolCalls = new ArrayList<>();

    private volatile boolean cancelled = false;
    private volatile ChatStream<T, TCall> active;

    ToolLoopChatStream(ChatStream<T, TCall> first, ToolLoop loop, Function<TCall, ToolCall> extractor) {
        this(first, loop, extractor, null, null, null);
    }

    ToolLoopChatStream(ChatStream<T, TCall> first, ToolLoop loop, Function<TCall, ToolCall> extractor,
                       Function<T, String> deltaExtractor, AgentEventScope.Binding eventBinding) {
        this(first, loop, extractor, deltaExtractor, eventBinding, null);
    }

    ToolLoopChatStream(ChatStream<T, TCall> first, ToolLoop loop, Function<TCall, ToolCall> extractor,
                       Function<T, String> deltaExtractor, AgentEventScope.Binding eventBinding,
                       AgentCallContext.Binding callBinding) {
        this.first = first;
        this.loop = loop;
        this.extractor = extractor;
        this.deltaExtractor = deltaExtractor;
        this.eventBinding = eventBinding;
        this.callBinding = callBinding;
        this.active = first;
    }

    // ==================== 回调注册 ====================

    @Override
    public ToolLoopChatStream<T, TCall> onData(Consumer<? super T> consumer) {
        dataConsumers.add(consumer);
        return this;
    }

    @Override
    public ToolLoopChatStream<T, TCall> onReasoning(Consumer<? super String> consumer) {
        reasoningConsumers.add(consumer);
        return this;
    }

    @Override
    public ToolLoopChatStream<T, TCall> onToolCall(Consumer<? super TCall> consumer) {
        toolCallConsumers.add(consumer);
        return this;
    }

    @Override
    public ToolLoopChatStream<T, TCall> onToolCalls(Consumer<? super List<TCall>> consumer) {
        toolCallsConsumers.add(consumer);
        return this;
    }

    @Override
    public ToolLoopChatStream<T, TCall> onFinish(Consumer<? super ChatAggregateResult<TCall>> consumer) {
        finishConsumers.add(consumer);
        return this;
    }

    @Override
    public ToolLoopChatStream<T, TCall> onComplete(Runnable consumer) {
        completeConsumers.add(consumer);
        return this;
    }

    @Override
    public ToolLoopChatStream<T, TCall> onError(Consumer<? super Throwable> consumer) {
        errorConsumers.add(consumer);
        return this;
    }

    // ==================== 驱动 ====================

    @Override
    public ToolLoopChatStream<T, TCall> start() {
        VirtualThreads.start(this::run);
        return this;
    }

    @Override
    public void cancel() {
        cancelled = true;
        ChatStream<T, TCall> current = active;
        if (current != null) {
            current.cancel();
        }
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

    private void run() {
        // 事件绑定在创建流时捕获；读取发生在本虚拟线程里，ThreadLocal 不会自动继承，
        // 所以这里显式恢复作用域，工具循环里的 FunctionToolLoop 才能发 tool 事件。
        Runnable body = eventBinding == null
                ? this::runLoop
                : () -> AgentEventScope.runWith(eventBinding, this::runLoop);
        // 调用者身份必须一起搬运：工具执行发生在读流线程里，
        // 不绑定的话 FunctionToolLoop 看到的 AgentCallContext 是 null，
        // 带 scope 的工具会被误判为「无权限」。
        if (callBinding == null || callBinding.context() == null) {
            body.run();
        } else {
            callBinding.run(body);
        }
    }

    private void runLoop() {
        ChatStream<T, TCall> current = first;
        ChatAggregateResult<TCall> last = null;
        try {
            for (int round = 1; !cancelled; round++) {
                // 实时回调转发到本轮流：先注册再启动，保证不丢头部 chunk
                if (eventBinding != null) {
                    current.onData(chunk -> {
                        String delta = extractDelta(chunk);
                        if (delta != null && !delta.isEmpty()) {
                            AgentEventScope.emit(eventBinding, new AgentEvent.MessageDelta(delta));
                        }
                    });
                    current.onReasoning(text -> {
                        if (text != null && !text.isEmpty()) {
                            AgentEventScope.emit(eventBinding, new AgentEvent.ReasoningDelta(text));
                        }
                    });
                }
                dataConsumers.forEach(current::onData);
                reasoningConsumers.forEach(current::onReasoning);
                current.start().await();                            // 本轮读完；异常走 catch

                ChatAggregateResult<TCall> result = current.result().join();
                allChunks.addAll(current.collect().join());
                last = result;

                List<TCall> calls = result.toolCalls() == null ? List.of() : result.toolCalls();
                allToolCalls.addAll(calls);
                if (calls.isEmpty()) {
                    break;                                          // 模型说完了
                }
                if (round >= MAX_ROUNDS) {
                    log.warn("工具循环达到 {} 轮上限，停止续发（模型可能在反复调工具）", MAX_ROUNDS);
                    break;
                }
                ChatStream<?, ?> next = loop.next(result.content(), calls.stream().map(extractor).toList());
                if (next == null) {
                    break;                                          // 处理器主动结束
                }
                current = unwrap(next);
                active = current;
            }
            finish(last);
        } catch (Throwable t) {
            if (cancelled) {
                finish(last);                                       // 取消视为正常结束（对齐 Stream.await 的约定）
                return;
            }
            fail(t);
        }
    }

    /**
     * 拆掉续发轮流的循环包装：工厂是全局的，{@code client.stream()} 一定会再包一层；
     * 直接驱动其裸流，保证扁平结构（否则每轮嵌套一层线程与包装）。
     */
    @SuppressWarnings("unchecked")
    private ChatStream<T, TCall> unwrap(ChatStream<?, ?> next) {
        if (next instanceof ToolLoopChatStream<?, ?> wrapped) {
            return (ChatStream<T, TCall>) wrapped.first;
        }
        return (ChatStream<T, TCall>) next;
    }

    private void finish(ChatAggregateResult<TCall> last) {
        if (!cancelled) {
            List<TCall> snapshot = List.copyOf(allToolCalls);
            toolCallConsumers.forEach(consumer -> snapshot.forEach(consumer));
            toolCallsConsumers.forEach(consumer -> consumer.accept(snapshot));
            completeConsumers.forEach(Runnable::run);
        }
        collectFuture.complete(List.copyOf(allChunks));
        ChatAggregateResult<TCall> aggregate = new ChatAggregateResult<>(
                last == null ? "" : last.content(),
                last == null ? "" : last.reasoning(),
                List.copyOf(allToolCalls),
                last == null ? new ChatAggregateResult.TokenUsage(0, 0, 0) : last.usage(),
                !cancelled && (last == null || last.success())
        );
        finishConsumers.forEach(consumer -> consumer.accept(aggregate));
        emitUsage(aggregate);
        emitTerminal(cancelled ? "cancelled" : "completed");
        resultFuture.complete(aggregate);
        future.complete(null);
    }

    private void fail(Throwable t) {
        if (terminalEmitted.compareAndSet(false, true)) {
            String message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            AgentEventScope.emit(eventBinding, new AgentEvent.Error("llm_stream_error", message, false));
            AgentEventScope.emit(eventBinding, new AgentEvent.Done("failed"));
        }
        collectFuture.completeExceptionally(t);
        resultFuture.completeExceptionally(t);
        errorConsumers.forEach(consumer -> consumer.accept(t));
        future.completeExceptionally(t);
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

    private void emitUsage(ChatAggregateResult<TCall> aggregate) {
        if (eventBinding == null || aggregate == null || aggregate.usage() == null) {
            return;
        }
        ChatAggregateResult.TokenUsage usage = aggregate.usage();
        if (usage.totalTokens() <= 0 && usage.promptTokens() <= 0 && usage.completionTokens() <= 0) {
            return;
        }
        AgentEventScope.emit(eventBinding, new AgentEvent.Usage(
                usage.promptTokens(), usage.completionTokens(), usage.totalTokens(), null));
    }

    private void emitTerminal(String status) {
        if (terminalEmitted.compareAndSet(false, true)) {
            AgentEventScope.emit(eventBinding, new AgentEvent.Done(status));
        }
    }
}
