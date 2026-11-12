package org.wutong.tiangong.llm.event.stream;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface ChatStream<T, TCall> extends Stream<T> {

    @Override
    ChatStream<T, TCall> onData(Consumer<? super T> consumer);

    @Override
    ChatStream<T, TCall> onComplete(Runnable consumer);

    @Override
    ChatStream<T, TCall> onError(Consumer<? super Throwable> consumer);

    @Override
    ChatStream<T, TCall> start();

    ChatStream<T, TCall> onReasoning(Consumer<? super String> consumer);

    /**
     * 注册工具调用订阅者（可多次注册，全部收到）。<br>
     * 语义：<b>流结束时统一回调聚合后的完整工具调用</b>（按出现顺序），
     * Deepseek 的分片 arguments 已拼接、Ollama 的完整调用原样透出。
     * 需要实时感知原始分片可用 {@link #onData}。
     */
    ChatStream<T, TCall> onToolCall(Consumer<? super TCall> consumer);

    /**
     * 注册工具调用列表订阅者：流结束时<b>一次回调全部完整工具调用</b>（与 {@link #onToolCall} 逐条回调互补）
     */
    ChatStream<T, TCall> onToolCalls(Consumer<? super List<TCall>> consumer);

    /**
     * 注册完成订阅者：流结束时回调<b>聚合结果</b>（回调式取结果，与 {@link #result()} 等价）<br>
     * 仅正常结束/取消时触发；异常走 {@link #onError}。
     */
    ChatStream<T, TCall> onFinish(Consumer<? super ChatAggregateResult<TCall>> consumer);

    /**
     * 等流结束，返回聚合的完整对话结果
     */
    CompletableFuture<ChatAggregateResult<TCall>> result();

    /**
     * 流式对话的聚合结果（内部类，随 ChatStream 走）。
     *
     * @param <TTool> 各家 ToolCall 类型（Ollama → ToolCall，Deepseek → ToolCallDelta）
     */
    record ChatAggregateResult<TTool>(
            String content,          // 完整回复
            String reasoning,        // 完整思考
            List<TTool> toolCalls,   // 所有 tool call
            TokenUsage usage,        // token 统计
            boolean success          // 是否正常结束
    ) {

        public record TokenUsage(
                long promptTokens,
                long completionTokens,
                long totalTokens
        ) {
        }
    }

}
