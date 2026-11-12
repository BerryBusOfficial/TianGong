package com.taixu.tiangong.llm.event.stream;

import com.taixu.tiangong.common.agent.call.AgentCallContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolLoopContextPropagationTest {

    @Test
    void toolLoopRunsWithCallerContextBound() {
        AgentCallContext context = new AgentCallContext("u-1", "tenant-1",
                Set.of("report:draft"), "trace-1");
        AgentCallContext.Binding callBinding = new AgentCallContext.Binding(context);

        ScriptedStream first = new ScriptedStream("thinking", List.of("draft_report"));
        ScriptedStream second = new ScriptedStream("final", List.of());

        ToolLoop loop = (content, calls) -> {
            CapturingLoop.captured = AgentCallContext.current();
            return second;
        };

        // 直接构造循环流：模拟 ToolLoopSupport.wrap 捕获到的 callBinding。
        ToolLoopChatStream<String, String> stream = new ToolLoopChatStream<>(
                first, loop, call -> new ToolCall("id-1", call, "{}"), null, null, callBinding);
        stream.start().await();

        assertEquals("u-1", CapturingLoop.captured == null ? null : CapturingLoop.captured.userId());
    }

    static final class CapturingLoop {
        static AgentCallContext captured;
    }

    /** 最简脚本流：start 时同步完成，result 带指定工具调用。 */
    static final class ScriptedStream implements ChatStream<String, String> {

        private final String content;
        private final List<String> calls;
        private final CompletableFuture<Void> future = new CompletableFuture<>();

        ScriptedStream(String content, List<String> calls) {
            this.content = content;
            this.calls = calls;
        }

        @Override
        public ChatStream<String, String> onData(Consumer<? super String> consumer) {
            return this;
        }

        @Override
        public ChatStream<String, String> onReasoning(Consumer<? super String> consumer) {
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
            return this;
        }

        @Override
        public ChatStream<String, String> onError(Consumer<? super Throwable> consumer) {
            return this;
        }

        @Override
        public ChatStream<String, String> start() {
            future.complete(null);
            return this;
        }

        @Override
        public CompletableFuture<Void> future() {
            return future;
        }

        @Override
        public CompletableFuture<List<String>> collect() {
            return CompletableFuture.completedFuture(List.of(content));
        }

        @Override
        public CompletableFuture<ChatAggregateResult<String>> result() {
            return CompletableFuture.completedFuture(new ChatAggregateResult<>(
                    content, "", calls, new ChatAggregateResult.TokenUsage(1, 1, 2), true));
        }

        @Override
        public void cancel() {
        }
    }
}
