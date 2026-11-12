package com.taixu.tiangong.llm.event.stream;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.taixu.tiangong.common.thread.VirtualThreads;
import com.taixu.tiangong.common.util.JsonUtil;
import com.taixu.tiangong.llm.api.ApiExceptionFactory;
import com.taixu.tiangong.llm.api.response.chat.chunk.DeepseekChatChunkResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Slf4j
@RequiredArgsConstructor
public class DeepseekChatStream implements ChatStream<DeepseekChatChunkResponse, DeepseekChatChunkResponse.ToolCallDelta> {

    private final InputStream stream;
    private final List<Consumer<? super DeepseekChatChunkResponse>> dataConsumers = new CopyOnWriteArrayList<>();
    private final List<Consumer<? super String>> reasoningConsumers = new CopyOnWriteArrayList<>();
    private final List<Consumer<? super DeepseekChatChunkResponse.ToolCallDelta>> toolCallConsumers = new CopyOnWriteArrayList<>();
    private final List<Consumer<? super List<DeepseekChatChunkResponse.ToolCallDelta>>> toolCallsConsumers = new CopyOnWriteArrayList<>();
    private final List<Consumer<? super ChatAggregateResult<DeepseekChatChunkResponse.ToolCallDelta>>> finishConsumers = new CopyOnWriteArrayList<>();
    private final List<Runnable> completeConsumers = new CopyOnWriteArrayList<>();
    private final List<Consumer<? super Throwable>> errorConsumers = new CopyOnWriteArrayList<>();
    private final CompletableFuture<Void> future = new CompletableFuture<>();
    private final AtomicBoolean completed = new AtomicBoolean();
    private final StringBuilder contentBuffer = new StringBuilder();
    private final StringBuilder reasoningBuffer = new StringBuilder();
    private final List<DeepseekChatChunkResponse> chunkBuffer = new ArrayList<>();
    private final CompletableFuture<List<DeepseekChatChunkResponse>> collectFuture = new CompletableFuture<>();
    private final CompletableFuture<ChatAggregateResult<DeepseekChatChunkResponse.ToolCallDelta>> resultFuture = new CompletableFuture<>();
    private final Map<Integer, StringBuilder> toolCallArgs = new HashMap<>();   // index → arguments 拼接
    private final Map<Integer, DeepseekChatChunkResponse.ToolCallDelta> toolCallMeta = new HashMap<>();  // index → 首个 meta
    private volatile boolean cancelled = false;
    private long promptTokens;
    private long completionTokens;
    private long totalTokens;

    @Override
    public DeepseekChatStream onData(Consumer<? super DeepseekChatChunkResponse> consumer) {
        dataConsumers.add(consumer);
        return this;
    }

    @Override
    public DeepseekChatStream onReasoning(Consumer<? super String> consumer) {
        reasoningConsumers.add(consumer);
        return this;
    }

    @Override
    public DeepseekChatStream onToolCall(Consumer<? super DeepseekChatChunkResponse.ToolCallDelta> consumer) {
        toolCallConsumers.add(consumer);
        return this;
    }

    @Override
    public DeepseekChatStream onToolCalls(Consumer<? super List<DeepseekChatChunkResponse.ToolCallDelta>> consumer) {
        toolCallsConsumers.add(consumer);
        return this;
    }

    @Override
    public DeepseekChatStream onFinish(Consumer<? super ChatAggregateResult<DeepseekChatChunkResponse.ToolCallDelta>> consumer) {
        finishConsumers.add(consumer);
        return this;
    }

    @Override
    public DeepseekChatStream onComplete(Runnable consumer) {
        completeConsumers.add(consumer);
        return this;
    }

    @Override
    public DeepseekChatStream onError(Consumer<? super Throwable> consumer) {
        errorConsumers.add(consumer);
        return this;
    }

    @Override
    public void cancel() {
        cancelled = true;
        try {
            stream.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public DeepseekChatStream start() {
        VirtualThreads.start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while (!cancelled && (line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String json = line.substring(5).trim();
                    if ("[DONE]".equals(json)) {
                        break;
                    }
                    JsonNode error = JsonUtil.getJSON_MAPPER()
                            .readTree(json)
                            .get("error");
                    if (error != null && !error.isNull()) {
                        throw ApiExceptionFactory.from(0, error.toString());
                    }
                    DeepseekChatChunkResponse response = JsonUtil.fromJson(json, DeepseekChatChunkResponse.class);
                    process(response);
                }
                if (!cancelled) {
                    List<DeepseekChatChunkResponse.ToolCallDelta> complete = snapshotToolCalls();
                    complete.forEach(tool -> toolCallConsumers.forEach(c -> c.accept(tool)));   // ← onToolCall 收到完整调用
                    if (!toolCallsConsumers.isEmpty()) {
                        toolCallsConsumers.forEach(c -> c.accept(complete));                    // ← onToolCalls 整批回调
                    }
                }
                if (!cancelled) triggerComplete();
                collectFuture.complete(List.copyOf(chunkBuffer));
                ChatAggregateResult<DeepseekChatChunkResponse.ToolCallDelta> aggregate = new ChatAggregateResult<>(
                        contentBuffer.toString(),
                        reasoningBuffer.toString(),
                        snapshotToolCalls(),
                        new ChatAggregateResult.TokenUsage(promptTokens, completionTokens, totalTokens),
                        !cancelled
                );
                if (!finishConsumers.isEmpty()) {
                    finishConsumers.forEach(c -> c.accept(aggregate));                          // ← onFinish 回调聚合结果
                }
                resultFuture.complete(aggregate);

                future.complete(null);
            } catch (Exception e) {
                collectFuture.completeExceptionally(e);
                resultFuture.completeExceptionally(e);
                if (!cancelled && !errorConsumers.isEmpty()) {
                    errorConsumers.forEach(consumer -> consumer.accept(e));
                }
                future.completeExceptionally(e);
            }
        });

        return this;
    }

    @Override
    public CompletableFuture<Void> future() {
        return future;
    }

    @Override
    public CompletableFuture<List<DeepseekChatChunkResponse>> collect() {
        return collectFuture;
    }

    @Override
    public CompletableFuture<ChatAggregateResult<DeepseekChatChunkResponse.ToolCallDelta>> result() {
        return resultFuture;
    }

    private void process(DeepseekChatChunkResponse response) {
        chunkBuffer.add(response);                                 // 聚合：原始 chunk
        if (!dataConsumers.isEmpty()) {
            dataConsumers.forEach(consumer -> consumer.accept(response));
        }

        // ⚠ usage 必须在 choices 判空之前累加：开了 stream_options.include_usage 后，
        // 末尾会来一个 choices 为空数组、只带 usage 的块（OpenaiChatStream 有对照组实测：
        // 挪到判空之后 token 统计从 11 变 0，且没有任何报错）。
        accumulateUsage(response);

        if (response.choices() == null || response.choices().isEmpty()) {
            return;
        }
        DeepseekChatChunkResponse.Choice choice = response.choices().getFirst();
        DeepseekChatChunkResponse.Delta delta = choice.delta();
        if (delta == null) {
            return;
        }

        accumulate(delta);
        dispatchReasoning(delta);
        dispatchToolCalls(delta);
        dispatchFinish(choice);
    }

    /**
     * 内部聚合（独立于用户回调，无条件执行）
     */
    private void accumulate(DeepseekChatChunkResponse.Delta delta) {
        if (delta.content() != null) contentBuffer.append(delta.content());
        if (delta.reasoningContent() != null) reasoningBuffer.append(delta.reasoningContent());
    }

    private void accumulateUsage(DeepseekChatChunkResponse response) {
        if (response.usage() == null) {
            return;
        }
        promptTokens = response.usage().promptTokens();
        completionTokens = response.usage().completionTokens();
        totalTokens = response.usage().totalTokens();
    }

    private void dispatchReasoning(DeepseekChatChunkResponse.Delta delta) {
        if (reasoningConsumers.isEmpty()) {
            return;
        }
        String reasoning = delta.reasoningContent();
        if (reasoning != null && !reasoning.isEmpty()) {
            reasoningConsumers.forEach(consumer -> consumer.accept(reasoning));
        }
    }

    private void dispatchToolCalls(DeepseekChatChunkResponse.Delta delta) {
        if (delta.toolCalls() == null) return;
        for (DeepseekChatChunkResponse.ToolCallDelta tool : delta.toolCalls()) {
            toolCallMeta.putIfAbsent(tool.index(), tool);                    // id/type/name 取首个非 null
            if (tool.function() != null && tool.function().arguments() != null) {
                toolCallArgs.computeIfAbsent(tool.index(), k -> new StringBuilder())
                        .append(tool.function().arguments());                // arguments 按片拼接
            }
        }
    }

    private List<DeepseekChatChunkResponse.ToolCallDelta> snapshotToolCalls() {
        return toolCallMeta.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    var meta = e.getValue();
                    String args = toolCallArgs.getOrDefault(e.getKey(), new StringBuilder()).toString();
                    return new DeepseekChatChunkResponse.ToolCallDelta(
                            e.getKey(), meta.id(), meta.type(),
                            new DeepseekChatChunkResponse.FunctionDelta(meta.function().name(), args));
                })
                .toList();
    }

    private void dispatchFinish(DeepseekChatChunkResponse.Choice choice) {
        if (choice.finishReason() != null) {
            triggerComplete();       // 幂等
        }
    }

    private void triggerComplete() {
        if (completed.compareAndSet(false, true)) {
            if (!completeConsumers.isEmpty()) {
                completeConsumers.forEach(Runnable::run);
            }
        }
    }
}