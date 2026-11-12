package com.taixu.tiangong.test;

import com.taixu.tiangong.annotations.annotation.framework.component.LlmClient;
import com.taixu.tiangong.annotations.annotation.framework.component.LlmOptions;
import org.junit.jupiter.api.Test;
import com.taixu.tiangong.llm.api.request.chat.ChatRequest;
import com.taixu.tiangong.llm.api.request.chat.OpenaiChatRequest;
import com.taixu.tiangong.llm.api.response.chat.chunk.OpenaiChatChunkResponse;
import com.taixu.tiangong.llm.api.response.model.OpenaiModelsResponse;
import com.taixu.tiangong.llm.client.OpenaiLlmClient;
import com.taixu.tiangong.llm.event.stream.OpenaiChatStream;
import com.taixu.tiangong.llm.message.HumanMessage;
import com.taixu.tiangong.llm.message.Message;
import com.taixu.tiangong.llm.options.chat.OpenaiChatOptions;
import com.taixu.tiangong.llm.provider.OpenaiProvider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenAI 本家协议 provider 的离线验证：不联网，全部喂固定字节/JSON。<br>
 * 重点覆盖两处：
 * <ol>
 *     <li>请求体的字段映射（SNAKE_CASE + null 不下发）；</li>
 *     <li>流式解析，特别是 <b>末尾那个 choices 为空、只带 usage 的块</b>——
 *     本家在开了 include_usage 后必发这块，漏掉它 token 统计就永远是 0。</li>
 * </ol>
 */
class OpenaiProviderTest {

    private final OpenaiProvider provider = new OpenaiProvider();

    // ==================== 请求体 ====================

    @Test
    void chatRequestMapsToSnakeCaseAndOmitsUnset() {
        ChatRequest request = ChatRequest.builder()
                .model("gpt-4o")
                .messages(List.<Message>of(new HumanMessage("你好")))
                .stream(true)
                .chatOptions(OpenaiChatOptions.builder()
                        .temperature(0.3)
                        .topP(0.9)
                        .maxCompletionTokens(512)
                        .reasoningEffort(OpenaiChatRequest.ReasoningEffort.LOW)
                        .build())
                .build();

        Map<String, Object> map = provider.toChatRequest(request).toMap();

        assertEquals("gpt-4o", map.get("model"));
        assertEquals(Boolean.TRUE, map.get("stream"));
        assertEquals(0.3d, ((Number) map.get("temperature")).doubleValue());
        assertEquals(0.9d, ((Number) map.get("top_p")).doubleValue());
        assertEquals(512, ((Number) map.get("max_completion_tokens")).intValue());
        assertEquals("low", map.get("reasoning_effort"));
        assertTrue(map.containsKey("messages"));

        // 没设的字段一个都不下发：把推理模型专属参数塞给 gpt-4o 会被本家拒绝
        assertFalse(map.containsKey("verbosity"), "不应下发 verbosity");
        assertFalse(map.containsKey("max_tokens"), "不应下发 max_tokens");
        assertFalse(map.containsKey("stop"), "不应下发 stop");
        assertFalse(map.containsKey("user"), "不应下发 user");
        assertFalse(map.containsKey("tools"), "不应下发 tools");
        assertFalse(map.containsKey("tool_choice"), "无 tools 不应下发 tool_choice");
    }

    @Test
    void chatRequestFallsBackToDefaultsWhenOptionsAbsent() {
        ChatRequest request = ChatRequest.builder()
                .model("gpt-5")
                .messages(List.<Message>of(new HumanMessage("hi")))
                .build();

        OpenaiChatRequest body = provider.toChatRequest(request);

        assertNotNull(body);
        assertEquals(1.0d, body.getTemperature().doubleValue());
        assertEquals(1.0d, body.getTopP().doubleValue());
        // 无工具请求不下发 tool_choice —— OpenAI 协议校验它必须伴随 tools（网关会 400 拒绝）
        assertNull(body.getToolChoice(), "无 tools 不应下发 tool_choice");
        assertNull(body.getReasoningEffort(), "未配置不应默认给推理强度");
    }

    @Test
    void toolChoiceDefaultsToAutoOnlyWhenToolsPresent() {
        ChatRequest request = ChatRequest.builder()
                .model("gpt-5")
                .messages(List.<Message>of(new HumanMessage("hi")))
                .tools(List.of(ChatRequest.Tool.builder()
                        .function(ChatRequest.Tool.Function.builder()
                                .name("report_finding")
                                .parameters(ChatRequest.JsonSchema.builder().build())
                                .build())
                        .build()))
                .build();

        OpenaiChatRequest body = provider.toChatRequest(request);

        assertEquals(OpenaiChatRequest.ToolChoice.Type.AUTO, body.getToolChoice().value(),
                "有 tools 时 tool_choice 默认 auto 的既有行为保持不变");
        assertTrue(body.toMap().containsKey("tool_choice"));
    }

    // ==================== 响应解析 ====================

    @Test
    void parsesNonStreamResponse() {
        String json = """
                {
                  "id": "chatcmpl-abc",
                  "object": "chat.completion",
                  "created": 1700000000,
                  "model": "gpt-4o",
                  "choices": [
                    {"index": 0, "message": {"role": "assistant", "content": "你好，梧桐"},
                     "finish_reason": "stop"}
                  ],
                  "usage": {"prompt_tokens": 9, "completion_tokens": 5, "total_tokens": 14}
                }
                """;

        var response = provider.parseChatResponse(json);

        assertEquals("你好，梧桐", response.getContent());
        assertEquals(14L, response.usage().totalTokens());
        assertEquals("stop", response.choices().getFirst().finishReason());
    }

    @Test
    void parsesModelsResponse() {
        String json = """
                {"object": "list", "data": [
                  {"id": "gpt-4o", "object": "model", "created": 1715367049, "owned_by": "system"}
                ]}
                """;

        OpenaiModelsResponse models = provider.parseModelsResponse(json);

        assertEquals(1, models.data().size());
        assertEquals("gpt-4o", models.data().getFirst().id());
        assertEquals("system", models.data().getFirst().ownedBy());
    }

    // ==================== 流式 ====================

    @Test
    void streamAggregatesContentReasoningToolCallsAndUsage() {
        String sse = String.join("\n",
                sseChunk("""
                        {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt-4o",
                         "choices":[{"index":0,"delta":{"role":"assistant","content":"你好"},"finish_reason":null}]}
                        """),
                sseChunk("""
                        {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt-4o",
                         "choices":[{"index":0,"delta":{"reasoning_content":"先想想"},"finish_reason":null}]}
                        """),
                sseChunk("""
                        {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt-4o",
                         "choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function",
                          "function":{"name":"get_sys_time","arguments":"{\\"unit\\":"}}]},"finish_reason":null}]}
                        """),
                sseChunk("""
                        {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt-4o",
                         "choices":[{"index":0,"delta":{"tool_calls":[{"index":0,
                          "function":{"arguments":"\\"hour\\"}"}}]},"finish_reason":null}]}
                        """),
                sseChunk("""
                        {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt-4o",
                         "choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}
                        """),
                // ⚠ 本家的 usage 尾块：choices 是空数组，漏读它 token 统计就是 0
                sseChunk("""
                        {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt-4o",
                         "choices":[],"usage":{"prompt_tokens":11,"completion_tokens":7,"total_tokens":18}}
                        """),
                "data: [DONE]",
                "");

        OpenaiChatStream stream = new OpenaiChatStream(bytes(sse));
        stream.start().await();
        var result = stream.result().join();

        assertEquals("你好", result.content());
        assertEquals("先想想", result.reasoning());
        assertTrue(result.success());

        assertEquals(1, result.toolCalls().size());
        OpenaiChatChunkResponse.ToolCallDelta tool = result.toolCalls().getFirst();
        assertEquals("call_1", tool.id());
        assertEquals("get_sys_time", tool.function().name());
        assertEquals("{\"unit\":\"hour\"}", tool.function().arguments(), "arguments 必须按片拼接");

        assertEquals(11L, result.usage().promptTokens(), "usage-only 尾块必须被读到");
        assertEquals(7L, result.usage().completionTokens());
        assertEquals(18L, result.usage().totalTokens());
    }

    @Test
    void streamErrorIsNotSilent() {
        // 截断的 JSON：解析必然失败
        OpenaiChatStream stream = new OpenaiChatStream(
                bytes("data: {\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"坏数据\"\n"));

        RuntimeException error = assertThrows(RuntimeException.class, () -> stream.start().await());
        assertNotNull(error.getMessage());
    }

    /**
     * 钉住 {@code future()} 的语义：它<b>不阻塞</b>，拿到的是还没完成的 future。
     * 这正是原来 {@code join()} 名字骗人的地方 —— 想等流结束必须用 {@code await()}。
     */
    @Test
    void futureDoesNotBlockButAwaitDoes() {
        BlockingStream source = new BlockingStream(bytes("data: [DONE]\n"));
        OpenaiChatStream stream = new OpenaiChatStream(source);

        stream.start();
        assertFalse(stream.future().isDone(), "start() 后流还没跑完，future 不应已完成");

        source.release();
        assertTimeoutPreemptively(Duration.ofSeconds(5), stream::await);
        assertTrue(stream.future().isDone());
    }

    // ==================== 装配 ====================

    @Test
    void clientRegistersUnderOpenaiKeyAndUsesV1Endpoints() {
        var clientAnnotation = OpenaiLlmClient.class
                .getAnnotation(LlmClient.class);
        assertNotNull(clientAnnotation, "@LlmClient 缺失会导致 LlmRegistry 认不出 openai");
        assertEquals("openai", clientAnnotation.provider().getSimpleName().toLowerCase());

        var optionsAnnotation = OpenaiChatOptions.class
                .getAnnotation(LlmOptions.class);
        assertNotNull(optionsAnnotation, "@LlmOptions 缺失会导致 chat-options 绑定不到 openai:chat");
        assertEquals("openai", optionsAnnotation.provider().getSimpleName().toLowerCase());
        assertEquals("chat", optionsAnnotation.model().getSimpleName().toLowerCase());

        // LlmRegistry 依赖 (String baseUrl, String apiKey) 构造器，这里确保它存在且可用
        OpenaiLlmClient client = new OpenaiLlmClient("http://127.0.0.1:1", "sk-test");
        assertInstanceOf(OpenaiProvider.class, client.getProvider());
        assertEquals("/v1/chat/completions", client.getApi().getChatCompletionEndpoint());
        assertEquals("/v1/models", client.getApi().getModelsEndpoint());
        assertEquals("https://api.openai.com", OpenaiLlmClient.DEFAULT_BASE_URL);
    }

    // ==================== 工具 ====================

    private static InputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String sseChunk(String json) {
        return "data: " + json.replaceAll("\\s*\\n\\s*", " ").trim();
    }

    /**
     * 第一次 read 会挡住，直到 {@link #release()} —— 用来证明 future() 不阻塞。
     */
    private static final class BlockingStream extends InputStream {

        private final CountDownLatch gate = new CountDownLatch(1);
        private final byte[] payload;
        private int pos;

        private BlockingStream(InputStream delegate) {
            try {
                this.payload = delegate.readAllBytes();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        private void release() {
            gate.countDown();
        }

        @Override
        public int read() throws IOException {
            if (pos == 0) {
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            return pos < payload.length ? payload[pos++] & 0xff : -1;
        }

        @Override
        public void close() {
            gate.countDown();
        }
    }
}
