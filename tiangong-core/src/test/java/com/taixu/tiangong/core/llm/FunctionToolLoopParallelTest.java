package com.taixu.tiangong.core.llm;

import com.taixu.tiangong.common.type.impl.Openai;
import com.taixu.tiangong.core.function.AgentFunctionDefinition;
import com.taixu.tiangong.core.function.FunctionRegistry;
import com.taixu.tiangong.core.function.ToolInvoker;
import com.taixu.tiangong.llm.api.Api;
import com.taixu.tiangong.llm.api.request.chat.ChatRequest;
import com.taixu.tiangong.llm.api.response.chat.chunk.ChatChunkResponse;
import com.taixu.tiangong.llm.client.LlmClient;
import com.taixu.tiangong.llm.event.result.ChatResult;
import com.taixu.tiangong.llm.event.stream.ChatStream;
import com.taixu.tiangong.llm.event.stream.ToolCall;
import com.taixu.tiangong.llm.provider.OpenaiProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FunctionToolLoopParallelTest {

    @Test
    void multipleToolCallsRunConcurrently() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        FunctionRegistry registry = new FunctionRegistry();
        registry.register(tool("a", args -> {
            started.countDown();
            await(release);
            return "a";
        }));
        registry.register(tool("b", args -> {
            started.countDown();
            await(release);
            return "b";
        }));

        FunctionToolLoop loop = new FunctionToolLoop(new NullClient(),
                ChatRequest.builder().model("test").messages(List.of()).build(), registry);
        CompletableFuture<Void> call = CompletableFuture.runAsync(() -> loop.next("", List.of(
                new ToolCall("1", "a", "{}"),
                new ToolCall("2", "b", "{}"))));
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS),
                    "两个工具应同时进入执行；串行执行时第二个永远等不到第一个释放");
        } finally {
            release.countDown();
        }
        call.get(2, TimeUnit.SECONDS);
    }

    private static AgentFunctionDefinition tool(String name, ToolInvoker invoker) {
        return new AgentFunctionDefinition(name, "test", "", null, null, List.of(), invoker);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static final class NullClient implements LlmClient<Openai, OpenaiProvider> {

        @Override
        public OpenaiProvider getProvider() {
            return null;
        }

        @Override
        public Api getApi() {
            return null;
        }

        @Override
        public ChatResult<? extends com.taixu.tiangong.llm.api.response.chat.ChatResponse<? extends Openai>> chat(
                ChatRequest chatRequest) {
            return null;
        }

        @Override
        public ChatStream<? extends ChatChunkResponse<? extends Openai>, ?> stream(ChatRequest request) {
            return null;
        }
    }
}
