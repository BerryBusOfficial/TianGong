package org.wutong.tiangong.llm.event.stream;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.wutong.tiangong.common.thread.VirtualThreads;
import org.wutong.tiangong.common.util.JsonUtil;
import org.wutong.tiangong.llm.api.ApiExceptionFactory;
import org.wutong.tiangong.llm.api.response.chat.OllamaChatResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Slf4j
@RequiredArgsConstructor
public class OllamaChatStream implements ChatStream<OllamaChatResponse, OllamaChatResponse.ToolCall> {

    private final InputStream stream;
    private final List<Consumer<? super OllamaChatResponse>> dataConsumers = new CopyOnWriteArrayList<>();
    private final List<Consumer<? super String>> reasoningConsumers = new CopyOnWriteArrayList<>();
    private final List<Consumer<? super OllamaChatResponse.ToolCall>> toolCallConsumers = new CopyOnWriteArrayList<>();
    private final List<Consumer<? super List<OllamaChatResponse.ToolCall>>> toolCallsConsumers = new CopyOnWriteArrayList<>();
    private final List<Consumer<? super ChatStream.ChatAggregateResult<OllamaChatResponse.ToolCall>>> finishConsumers = new CopyOnWriteArrayList<>();
    private final List<Runnable> completeConsumers = new CopyOnWriteArrayList<>();
    private final List<Consumer<? super Throwable>> errorConsumers = new CopyOnWriteArrayList<>();
    private final CompletableFuture<Void> future = new CompletableFuture<>();
    private final AtomicBoolean completed = new AtomicBoolean();
    private final StringBuilder contentBuffer = new StringBuilder();
    private final StringBuilder reasoningBuffer = new StringBuilder();
    private final List<OllamaChatResponse.ToolCall> toolCallBuffer = new ArrayList<>();
    private final List<OllamaChatResponse> chunkBuffer = new ArrayList<>();
    private final CompletableFuture<List<OllamaChatResponse>> collectFuture = new CompletableFuture<>();
    private final CompletableFuture<ChatStream.ChatAggregateResult<OllamaChatResponse.ToolCall>> resultFuture = new CompletableFuture<>();
    private volatile boolean cancelled = false;
    private long promptTokens, completionTokens;

    @Override
    public ChatStream<OllamaChatResponse, OllamaChatResponse.ToolCall> onReasoning(Consumer<? super String> consumer) {
        reasoningConsumers.add(consumer);
        return this;
    }

    @Override
    public ChatStream<OllamaChatResponse, OllamaChatResponse.ToolCall> onToolCall(Consumer<? super OllamaChatResponse.ToolCall> consumer) {
        toolCallConsumers.add(consumer);
        return this;
    }

    @Override
    public ChatStream<OllamaChatResponse, OllamaChatResponse.ToolCall> onToolCalls(Consumer<? super List<OllamaChatResponse.ToolCall>> consumer) {
        toolCallsConsumers.add(consumer);
        return this;
    }

    @Override
    public ChatStream<OllamaChatResponse, OllamaChatResponse.ToolCall> onFinish(Consumer<? super ChatStream.ChatAggregateResult<OllamaChatResponse.ToolCall>> consumer) {
        finishConsumers.add(consumer);
        return this;
    }

    @Override
    public OllamaChatStream onData(Consumer<? super OllamaChatResponse> consumer) {
        dataConsumers.add(consumer);
        return this;
    }

    @Override
    public OllamaChatStream onComplete(Runnable consumer) {
        completeConsumers.add(consumer);
        return this;
    }

    @Override
    public OllamaChatStream onError(Consumer<? super Throwable> consumer) {
        errorConsumers.add(consumer);
        return this;
    }

    @Override
    public CompletableFuture<List<OllamaChatResponse>> collect() {
        return collectFuture;
    }

    @Override
    public CompletableFuture<ChatStream.ChatAggregateResult<OllamaChatResponse.ToolCall>> result() {
        return resultFuture;
    }

    @Override
    public CompletableFuture<Void> future() {
        return future;
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
    public OllamaChatStream start() {
        VirtualThreads.start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while (!cancelled && (line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    // Ollama 返回裸 NDJSON（无 data: 前缀）；兼容 OpenAI 风格（有前缀）
                    String json = line.trim();
                    if (json.startsWith("data:")) {
                        json = json.substring(5).trim();
                    }
                    if ("[DONE]".equals(json)) {
                        break;
                    }
                    JsonNode error = JsonUtil.getJSON_MAPPER()
                            .readTree(json)
                            .get("error");
                    if (error != null && !error.isNull()) {
                        throw ApiExceptionFactory.from(0, error.toString());
                    }
                    OllamaChatResponse response = JsonUtil.fromJson(json, OllamaChatResponse.class);
                    process(response);
                }
                if (!cancelled) {
                    List<OllamaChatResponse.ToolCall> complete = List.copyOf(toolCallBuffer);
                    complete.forEach(tool -> toolCallConsumers.forEach(c -> c.accept(tool)));   // ← onToolCall 收到完整调用
                    if (!toolCallsConsumers.isEmpty()) {
                        toolCallsConsumers.forEach(c -> c.accept(complete));                    // ← onToolCalls 整批回调
                    }
                }
                if (!cancelled) triggerComplete();
                collectFuture.complete(List.copyOf(chunkBuffer));
                ChatStream.ChatAggregateResult<OllamaChatResponse.ToolCall> aggregate = new ChatStream.ChatAggregateResult<>(
                        contentBuffer.toString(),
                        reasoningBuffer.toString(),
                        List.copyOf(toolCallBuffer),
                        new ChatStream.ChatAggregateResult.TokenUsage(
                                promptTokens, completionTokens, promptTokens + completionTokens),
                        !cancelled
                );
                if (!finishConsumers.isEmpty()) {
                    finishConsumers.forEach(c -> c.accept(aggregate));                          // ← onFinish 回调聚合结果
                }
                resultFuture.complete(aggregate);
                future.complete(null);
            } catch (Exception e) {
                if (!cancelled && !errorConsumers.isEmpty()) {
                    errorConsumers.forEach(consumer -> consumer.accept(e));
                }
                future.completeExceptionally(e);
            }
        });
        return this;
    }

    private void process(OllamaChatResponse response) {
        chunkBuffer.add(response);
        if (!dataConsumers.isEmpty()) {
            dataConsumers.forEach(consumer -> consumer.accept(response));
        }

        if (response.message() == null) return;
        accumulate(response);
        dispatchThinking(response.message());
        dispatchFinish(response);
    }

    private void accumulate(OllamaChatResponse response) {
        var message = response.message();
        if (message.content() != null) contentBuffer.append(message.content());
        if (message.thinking() != null) reasoningBuffer.append(message.thinking());
        if (message.toolCalls() != null) toolCallBuffer.addAll(message.toolCalls());
        if (response.promptEvalCount() != null) promptTokens = response.promptEvalCount();
        if (response.evalCount() != null) completionTokens = response.evalCount();
    }


    private void dispatchThinking(OllamaChatResponse.ResponseMessage message) {
        if (reasoningConsumers.isEmpty()) return;

        String thinking = message.thinking();
        if (thinking != null && !thinking.isEmpty()) {
            reasoningConsumers.forEach(consumer -> consumer.accept(thinking));
        }
    }

    private void dispatchFinish(OllamaChatResponse response) {
        if (Boolean.TRUE.equals(response.done())) {
            triggerComplete();        // ← 幂等
        }
    }

    // 新增方法：
    private void triggerComplete() {
        if (completed.compareAndSet(false, true)) {
            if (!completeConsumers.isEmpty()) {
                completeConsumers.forEach(Runnable::run);
            }
        }
    }


}
