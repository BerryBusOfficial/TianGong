package org.wutong.tiangong.llm.event.stream;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.wutong.tiangong.common.thread.VirtualThreads;
import org.wutong.tiangong.common.util.JsonUtil;
import org.wutong.tiangong.llm.api.ApiExceptionFactory;
import org.wutong.tiangong.llm.api.response.chat.chunk.OpenaiChatChunkResponse;

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

/**
 * OpenAI 本家 SSE 流：逐行 {@code data: {...}}，以 {@code data: [DONE]} 收尾。<br>
 * 两处和 Deepseek 实现的关键差异：
 * <ol>
 *     <li><b>usage 单独成块</b>：开了 {@code stream_options.include_usage} 后，本家会在末尾
 *     补一个 <b>choices 为空数组</b>、只带 usage 的块。若在 choices 为空时直接 return，
 *     token 统计会永远是 0 —— 所以 usage 的累加必须在 choices 判空<b>之前</b>。</li>
 *     <li>本家不保证 delta 的非空性，逐片判 null。</li>
 * </ol>
 */
@Slf4j
@RequiredArgsConstructor
public class OpenaiChatStream implements ChatStream<OpenaiChatChunkResponse, OpenaiChatChunkResponse.ToolCallDelta> {

    private final InputStream stream;
    private final List<Consumer<? super OpenaiChatChunkResponse>> dataConsumers = new CopyOnWriteArrayList<>();
    private final List<Consumer<? super String>> reasoningConsumers = new CopyOnWriteArrayList<>();
    private final List<Consumer<? super OpenaiChatChunkResponse.ToolCallDelta>> toolCallConsumers = new CopyOnWriteArrayList<>();
    private final List<Consumer<? super List<OpenaiChatChunkResponse.ToolCallDelta>>> toolCallsConsumers = new CopyOnWriteArrayList<>();
    private final List<Consumer<? super ChatAggregateResult<OpenaiChatChunkResponse.ToolCallDelta>>> finishConsumers = new CopyOnWriteArrayList<>();
    private final List<Runnable> completeConsumers = new CopyOnWriteArrayList<>();
    private final List<Consumer<? super Throwable>> errorConsumers = new CopyOnWriteArrayList<>();
    private final CompletableFuture<Void> future = new CompletableFuture<>();
    private final AtomicBoolean completed = new AtomicBoolean();
    private final StringBuilder contentBuffer = new StringBuilder();
    private final StringBuilder reasoningBuffer = new StringBuilder();
    private final List<OpenaiChatChunkResponse> chunkBuffer = new ArrayList<>();
    private final CompletableFuture<List<OpenaiChatChunkResponse>> collectFuture = new CompletableFuture<>();
    private final CompletableFuture<ChatAggregateResult<OpenaiChatChunkResponse.ToolCallDelta>> resultFuture = new CompletableFuture<>();
    private final Map<Integer, StringBuilder> toolCallArgs = new HashMap<>();   // index → arguments 拼接
    private final Map<Integer, OpenaiChatChunkResponse.ToolCallDelta> toolCallMeta = new HashMap<>();  // index → 首个 meta
    private volatile boolean cancelled = false;
    private long promptTokens;
    private long completionTokens;
    private long totalTokens;

    @Override
    public OpenaiChatStream onData(Consumer<? super OpenaiChatChunkResponse> consumer) {
        dataConsumers.add(consumer);
        return this;
    }

    @Override
    public OpenaiChatStream onReasoning(Consumer<? super String> consumer) {
        reasoningConsumers.add(consumer);
        return this;
    }

    @Override
    public OpenaiChatStream onToolCall(Consumer<? super OpenaiChatChunkResponse.ToolCallDelta> consumer) {
        toolCallConsumers.add(consumer);
        return this;
    }

    @Override
    public OpenaiChatStream onToolCalls(Consumer<? super List<OpenaiChatChunkResponse.ToolCallDelta>> consumer) {
        toolCallsConsumers.add(consumer);
        return this;
    }

    @Override
    public OpenaiChatStream onFinish(Consumer<? super ChatAggregateResult<OpenaiChatChunkResponse.ToolCallDelta>> consumer) {
        finishConsumers.add(consumer);
        return this;
    }

    @Override
    public OpenaiChatStream onComplete(Runnable consumer) {
        completeConsumers.add(consumer);
        return this;
    }

    @Override
    public OpenaiChatStream onError(Consumer<? super Throwable> consumer) {
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
    public OpenaiChatStream start() {
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
                    OpenaiChatChunkResponse response = JsonUtil.fromJson(json, OpenaiChatChunkResponse.class);
                    process(response);
                }
                if (!cancelled) {
                    List<OpenaiChatChunkResponse.ToolCallDelta> complete = snapshotToolCalls();
                    complete.forEach(tool -> toolCallConsumers.forEach(c -> c.accept(tool)));   // ← onToolCall 收到完整调用
                    if (!toolCallsConsumers.isEmpty()) {
                        toolCallsConsumers.forEach(c -> c.accept(complete));                    // ← onToolCalls 整批回调
                    }
                }
                if (!cancelled) triggerComplete();
                collectFuture.complete(List.copyOf(chunkBuffer));
                ChatAggregateResult<OpenaiChatChunkResponse.ToolCallDelta> aggregate = new ChatAggregateResult<>(
                        contentBuffer.toString(),
                        reasoningBuffer.toString(),
                        snapshotToolCalls(),
                        new ChatAggregateResult.TokenUsage(promptTokens, completionTokens,
                                totalTokens > 0 ? totalTokens : promptTokens + completionTokens),
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
    public CompletableFuture<List<OpenaiChatChunkResponse>> collect() {
        return collectFuture;
    }

    @Override
    public CompletableFuture<ChatAggregateResult<OpenaiChatChunkResponse.ToolCallDelta>> result() {
        return resultFuture;
    }

    private void process(OpenaiChatChunkResponse response) {
        chunkBuffer.add(response);                                 // 聚合：原始 chunk
        if (!dataConsumers.isEmpty()) {
            dataConsumers.forEach(consumer -> consumer.accept(response));
        }

        // ⚠ usage 必须在 choices 判空之前累加：末尾那个只带 usage 的块 choices 是空数组。
        // 对照组实测：挪到判空之后，promptTokens 从 11 变 0（且没有任何报错）。
        accumulateUsage(response);

        if (response.choices() == null || response.choices().isEmpty()) {
            return;
        }
        OpenaiChatChunkResponse.Choice choice = response.choices().getFirst();
        OpenaiChatChunkResponse.Delta delta = choice.delta();
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
    private void accumulate(OpenaiChatChunkResponse.Delta delta) {
        if (delta.content() != null) contentBuffer.append(delta.content());
        if (delta.reasoningContent() != null) reasoningBuffer.append(delta.reasoningContent());
    }

    private void accumulateUsage(OpenaiChatChunkResponse response) {
        if (response.usage() == null) {
            return;
        }
        promptTokens = response.usage().promptTokens();
        completionTokens = response.usage().completionTokens();
        totalTokens = response.usage().totalTokens();
    }

    private void dispatchReasoning(OpenaiChatChunkResponse.Delta delta) {
        if (reasoningConsumers.isEmpty()) {
            return;
        }
        String reasoning = delta.reasoningContent();
        if (reasoning != null && !reasoning.isEmpty()) {
            reasoningConsumers.forEach(consumer -> consumer.accept(reasoning));
        }
    }

    private void dispatchToolCalls(OpenaiChatChunkResponse.Delta delta) {
        if (delta.toolCalls() == null) return;
        for (OpenaiChatChunkResponse.ToolCallDelta tool : delta.toolCalls()) {
            toolCallMeta.putIfAbsent(tool.index(), tool);                    // id/type/name 取首个非 null
            if (tool.function() != null && tool.function().arguments() != null) {
                toolCallArgs.computeIfAbsent(tool.index(), k -> new StringBuilder())
                        .append(tool.function().arguments());                // arguments 按片拼接
            }
        }
    }

    private List<OpenaiChatChunkResponse.ToolCallDelta> snapshotToolCalls() {
        return toolCallMeta.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    var meta = e.getValue();
                    String args = toolCallArgs.getOrDefault(e.getKey(), new StringBuilder()).toString();
                    return new OpenaiChatChunkResponse.ToolCallDelta(
                            e.getKey(), meta.id(), meta.type(),
                            new OpenaiChatChunkResponse.FunctionDelta(
                                    meta.function() == null ? null : meta.function().name(), args));
                })
                .toList();
    }

    private void dispatchFinish(OpenaiChatChunkResponse.Choice choice) {
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
