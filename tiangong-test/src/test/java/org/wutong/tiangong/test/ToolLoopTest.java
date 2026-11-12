package org.wutong.tiangong.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.wutong.tiangong.annotations.annotation.definition.BeanDefinition;
import org.wutong.tiangong.annotations.annotation.definition.Lifecycle;
import org.wutong.tiangong.annotations.annotation.framework.agent.AgentFunction;
import org.wutong.tiangong.common.type.impl.Openai;
import org.wutong.tiangong.core.container.process.impl.AgentFunctionProcessor;
import org.wutong.tiangong.core.function.FunctionRegistry;
import org.wutong.tiangong.core.llm.FunctionToolLoop;
import org.wutong.tiangong.llm.api.Api;
import org.wutong.tiangong.llm.api.request.chat.ChatRequest;
import org.wutong.tiangong.llm.api.response.chat.ChatResponse;
import org.wutong.tiangong.llm.api.response.chat.chunk.OpenaiChatChunkResponse;
import org.wutong.tiangong.llm.client.LlmClient;
import org.wutong.tiangong.llm.client.OpenaiLlmClient;
import org.wutong.tiangong.llm.event.result.ChatResult;
import org.wutong.tiangong.llm.event.stream.ChatStream;
import org.wutong.tiangong.llm.event.stream.OpenaiChatStream;
import org.wutong.tiangong.llm.event.stream.ToolCall;
import org.wutong.tiangong.llm.event.stream.ToolLoopChatStream;
import org.wutong.tiangong.llm.event.stream.ToolLoopSupport;
import org.wutong.tiangong.llm.message.AssistantMessage;
import org.wutong.tiangong.llm.message.HumanMessage;
import org.wutong.tiangong.llm.message.Message;
import org.wutong.tiangong.llm.message.ToolMessage;
import org.wutong.tiangong.llm.provider.OpenaiProvider;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 框架层自动工具循环测试：模型调工具 → 按注册表索引（如 {@code get_sys_time}）执行 →<br>
 * 结果回放续轮，直到模型不再请求工具 —— 节点代码零感知。<br>
 * <br>
 * 覆盖三层：
 * <ol>
 *     <li>{@link ToolLoopChatStream}：多轮驱动、回调转发、聚合语义、取消/错误；</li>
 *     <li>{@link FunctionToolLoop}：按短名查 {@link FunctionRegistry}、执行、回放消息序列；</li>
 *     <li>端到端：脚本化客户端 + 安装工厂 → 一次 {@code stream()} 自动跑完工具轮。</li>
 * </ol>
 */
class ToolLoopTest {

    private static final Function<OpenaiChatChunkResponse.ToolCallDelta, ToolCall> EXTRACTOR =
            delta -> new ToolCall(delta.id(),
                    delta.function() == null ? null : delta.function().name(),
                    delta.function() == null ? null : delta.function().arguments());

    /** 第一轮：正文 + 一个完整工具调用（arguments 一次性给全，不测拼接——那是裸流的既有用例） */
    private static final String ROUND_TOOL_CALL = String.join("\n",
            sseChunk("""
                    {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt-4o",
                     "choices":[{"index":0,"delta":{"role":"assistant","content":"让我查一下"},"finish_reason":null}]}
                    """),
            sseChunk("""
                    {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt-4o",
                     "choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function",
                      "function":{"name":"get_sys_time","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}
                    """),
            "data: [DONE]",
            "");

    /** 第二轮：模型拿到工具结果后的最终答复 */
    private static final String ROUND_FINAL = String.join("\n",
            sseChunk("""
                    {"id":"c2","object":"chat.completion.chunk","created":1,"model":"gpt-4o",
                     "choices":[{"index":0,"delta":{"content":"现在是 10:00:00"},"finish_reason":"stop"}]}
                    """),
            "data: [DONE]",
            "");

    // 每个用例后卸载静态工厂：防污染创建了 ApplicationFrameworkContext 的其它测试
    @AfterEach
    void uninstallFactory() {
        ToolLoopSupport.install(null);
    }

    // ==================== 1. 循环流（包装层） ====================

    @Test
    void loopStreamDrivesToolRoundThenFinalRound() {
        ChatRequest request = requestWithTools("现在几点？");
        List<ToolCall> forwardedToLoop = new ArrayList<>();
        AtomicInteger loopCalls = new AtomicInteger();

        ToolLoopSupport.install((req, client) -> (content, calls) -> {
            loopCalls.incrementAndGet();
            forwardedToLoop.addAll(calls);
            // 续轮也让客户端走一遍 wrap（与真实客户端一致）：循环流应拆掉包装、扁平驱动
            return ToolLoopSupport.wrap(new OpenaiChatStream(bytes(ROUND_FINAL)), request, client, EXTRACTOR);
        });

        ChatStream<OpenaiChatChunkResponse, OpenaiChatChunkResponse.ToolCallDelta> stream =
                ToolLoopSupport.wrap(new OpenaiChatStream(bytes(ROUND_TOOL_CALL)), request, null, EXTRACTOR);
        assertInstanceOf(ToolLoopChatStream.class, stream, "带工具的请求应升级为工具循环流");

        List<String> data = new ArrayList<>();
        List<ToolCall> toolCallbacks = new ArrayList<>();
        AtomicInteger completes = new AtomicInteger();
        stream.onData(chunk -> {
                    String text = chunk.getContent();
                    if (text != null) {
                        data.add(text);
                    }
                })
                .onToolCall(delta -> toolCallbacks.add(EXTRACTOR.apply(delta)))
                .onComplete(completes::incrementAndGet)
                .start()
                .await();

        assertEquals(1, loopCalls.get(), "应执行一轮工具");
        assertEquals(List.of(new ToolCall("call_1", "get_sys_time", "{}")), forwardedToLoop,
                "工具调用应带上完整 id / 名字 / 参数");
        assertEquals(List.of("让我查一下", "现在是 10:00:00"), data, "两轮正文都按轮实时透出");
        assertEquals(forwardedToLoop, toolCallbacks, "onToolCall 在整个循环结束时回调全部调用");
        assertEquals(1, completes.get(), "onComplete 只触发一次");

        var result = stream.result().join();
        assertEquals("现在是 10:00:00", result.content(), "聚合取最后一轮（最终答复）");
        assertEquals(1, result.toolCalls().size(), "聚合的 toolCalls 含全部轮次");
        assertEquals(3, stream.collect().join().size(), "collect 收集两轮的原始 chunk（2 + 1）");
    }

    @Test
    void noToolsOrNoFactoryOrRefusedFactoryKeepsSingleRoundStream() {
        ChatRequest noTools = ChatRequest.builder()
                .model("gpt-4o")
                .messages(List.<Message>of(new HumanMessage("你好")))
                .build();
        OpenaiChatStream raw = new OpenaiChatStream(bytes(ROUND_FINAL));
        assertSame(raw, ToolLoopSupport.wrap(raw, noTools, null, EXTRACTOR), "没下发工具 → 原样返回裸流");

        ToolLoopSupport.install((req, client) -> null);   // 工厂整体拒绝（等价于配置关闭）
        OpenaiChatStream refused = new OpenaiChatStream(bytes(ROUND_FINAL));
        assertSame(refused, ToolLoopSupport.wrap(refused, requestWithTools("x"), null, EXTRACTOR),
                "工厂拒绝（如配置关闭） → 原样返回裸流");
    }

    @Test
    void streamErrorsReachOnErrorAndAwait() {
        ToolLoopSupport.install((req, client) -> (content, calls) -> null);
        ChatStream<OpenaiChatChunkResponse, OpenaiChatChunkResponse.ToolCallDelta> stream =
                ToolLoopSupport.wrap(
                        // 截断的 JSON：解析必然失败
                        new OpenaiChatStream(bytes("data: {\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"坏数据\"\n")),
                        requestWithTools("x"), null, EXTRACTOR);

        AtomicReference<Throwable> error = new AtomicReference<>();
        stream.onError(error::set);

        assertThrows(RuntimeException.class, () -> stream.start().await());
        assertNotNull(error.get(), "流内异常应透到 onError，而不是被包装层吞掉");
    }

    // ==================== 2. 工具循环执行器（core） ====================

    @Test
    @SuppressWarnings("unchecked")
    void functionToolLoopExecutesByShortNameAndReplaysMessages() {
        FunctionRegistry registry = registryFor(new SystemToolStub());
        ScriptedClient client = new ScriptedClient();
        ChatRequest original = requestWithTools("现在几点？");

        FunctionToolLoop loop = new FunctionToolLoop(client, original, registry);
        ChatStream<?, ?> next = loop.next("", List.of(new ToolCall("call_1", "get_sys_time", "{}")));

        assertNotNull(next);
        assertEquals(1, client.requests.size(), "应只为续轮发起一次请求");
        assertEquals("gpt-4o", client.requests.getFirst().getModel(), "续轮请求应保留原 model");
        assertEquals(original.getTools(), client.requests.getFirst().getTools(), "续轮请求应保留原 tools");

        List<Message> messages = client.requests.getFirst().getMessages();
        assertEquals(3, messages.size(), "原始 + 助手调用 + 工具结果");
        assertInstanceOf(HumanMessage.class, messages.get(0));

        AssistantMessage assistant = assertInstanceOf(AssistantMessage.class, messages.get(1));
        assertEquals("", assistant.getContent());
        assertEquals(1, assistant.getToolCalls().size());
        var call = assistant.getToolCalls().getFirst();
        assertEquals("call_1", call.getId());
        assertEquals("function", call.getType());
        assertEquals("get_sys_time", call.getFunction().name());
        assertEquals("{}", call.getFunction().arguments());

        ToolMessage tool = assertInstanceOf(ToolMessage.class, messages.get(2));
        assertEquals("call_1", tool.getToolCallId(), "工具结果必须按 id 对回调用");
        assertTrue(tool.getContent().contains("2026:10:12 10:00:00"), "工具结果应回放给模型");

        // 协议形状：OpenAI 协议靠 tool_calls + tool_call_id 配对，字段名必须是 snake_case
        Map<String, Object> wire = new OpenaiProvider().toChatRequest(client.requests.getFirst()).toMap();
        List<Map<String, Object>> wireMessages = (List<Map<String, Object>>) wire.get("messages");
        Map<String, Object> assistantWire = wireMessages.get(1);
        assertEquals("assistant", assistantWire.get("role"));
        List<Map<String, Object>> wireCalls = (List<Map<String, Object>>) assistantWire.get("tool_calls");
        assertEquals("call_1", wireCalls.getFirst().get("id"));
        assertEquals("function", wireCalls.getFirst().get("type"));
        Map<String, Object> wireFunction = (Map<String, Object>) wireCalls.getFirst().get("function");
        assertEquals("get_sys_time", wireFunction.get("name"));
        assertEquals("{}", wireFunction.get("arguments"));
        Map<String, Object> toolWire = wireMessages.get(2);
        assertEquals("tool", toolWire.get("role"));
        assertEquals("call_1", toolWire.get("tool_call_id"));
    }

    @Test
    void toolFailuresAreFedBackInsteadOfBreakingTheLoop() {
        FunctionRegistry registry = registryFor(new SystemToolStub());

        // 未注册：前缀说明 + 不抛
        ScriptedClient unknownClient = new ScriptedClient();
        new FunctionToolLoop(unknownClient, requestWithTools("x"), registry)
                .next("", List.of(new ToolCall("c1", "no_such_tool", "{}")));
        ToolMessage unknown = (ToolMessage) unknownClient.requests.getFirst().getMessages().getLast();
        assertEquals("工具未注册: no_such_tool", unknown.getContent());
        assertEquals("c1", unknown.getToolCallId());

        // 执行抛异常：失败原因回放，工具消息 id 仍对上
        ScriptedClient boomClient = new ScriptedClient();
        new FunctionToolLoop(boomClient, requestWithTools("x"), registry)
                .next("", List.of(new ToolCall("c2", "boom", "{}")));
        ToolMessage failed = (ToolMessage) boomClient.requests.getFirst().getMessages().getLast();
        assertTrue(failed.getContent().startsWith("工具执行失败: "), "异常应转为结果文本: " + failed.getContent());
        assertTrue(failed.getContent().contains("工具内部炸了"));
        assertEquals("c2", failed.getToolCallId());
    }

    // ==================== 3. 端到端：一次 stream() 跑完工具轮 ====================

    @Test
    void endToEndAutoLoopExecutesToolAndContinuesConversation() {
        FunctionRegistry registry = registryFor(new SystemToolStub());
        ScriptedClient client = new ScriptedClient(ROUND_TOOL_CALL, ROUND_FINAL);
        ToolLoopSupport.install((request, c) -> new FunctionToolLoop(c, request, registry));

        ChatStream<OpenaiChatChunkResponse, OpenaiChatChunkResponse.ToolCallDelta> stream =
                client.stream(requestWithTools("现在几点？"));
        assertInstanceOf(ToolLoopChatStream.class, stream);

        List<String> data = new ArrayList<>();
        stream.onData(chunk -> {
            String text = chunk.getContent();
            if (text != null) {
                data.add(text);
            }
        }).start().await();

        assertEquals(List.of("让我查一下", "现在是 10:00:00"), data, "一次 stream() 应拿到完整答复");
        assertEquals(2, client.requests.size(), "工具调用后应自动续发第二轮");

        List<Message> second = client.requests.get(1).getMessages();
        assertEquals(3, second.size(), "续发请求里应带助手调用 + 工具结果");
        AssistantMessage assistant = assertInstanceOf(AssistantMessage.class, second.get(1));
        assertEquals("call_1", assistant.getToolCalls().getFirst().getId());
        ToolMessage tool = assertInstanceOf(ToolMessage.class, second.get(2));
        assertTrue(tool.getContent().contains("2026:10:12 10:00:00"));
    }

    @Test
    void autoLoopWorksOverRealHttpAgainstMockEndpoint() throws Exception {
        FunctionRegistry registry = registryFor(new SystemToolStub());
        List<String> requestBodies = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();

        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = (calls.getAndIncrement() == 0 ? ROUND_TOOL_CALL : ROUND_FINAL)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        try {
            OpenaiLlmClient client = new OpenaiLlmClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "sk-test");
            ToolLoopSupport.install((request, c) -> new FunctionToolLoop(c, request, registry));

            List<String> data = new ArrayList<>();
            client.stream(requestWithTools("现在几点？"))
                    .onData(chunk -> {
                        String text = chunk.getContent();
                        if (text != null) {
                            data.add(text);
                        }
                    })
                    .start()
                    .await();

            assertEquals(List.of("让我查一下", "现在是 10:00:00"), data, "真实 HTTP 链路上也应自动完成工具轮");
            assertEquals(2, requestBodies.size(), "mock 端应收到两轮请求");
            assertTrue(requestBodies.get(0).contains("\"tools\""), "首轮请求应下发工具定义");
            assertTrue(requestBodies.get(1).contains("tool_calls"), "续轮请求应带助手的 tool_calls");
            assertTrue(requestBodies.get(1).contains("tool_call_id"), "续轮请求应带工具结果消息");
            assertTrue(requestBodies.get(1).contains("2026:10:12 10:00:00"), "工具结果应回放给模型");
        } finally {
            server.stop(0);
        }
    }

    // ==================== 脚本化客户端（不联网） ====================

    /**
     * 按脚本依次吐流的 OpenAI 客户端：{@code stream()} 内部与真实客户端一样走
     * {@link ToolLoopSupport#wrap}，让「请求 → 循环流 → 工具执行 → 续轮」整条链路可离线验证。
     */
    private static final class ScriptedClient implements LlmClient<Openai, OpenaiProvider> {

        private final List<ChatRequest> requests = new ArrayList<>();
        private final List<String> rounds;
        private int index;

        private ScriptedClient(String... rounds) {
            this.rounds = List.of(rounds);
        }

        @Override
        public OpenaiProvider getProvider() {
            return null;
        }

        @Override
        public Api getApi() {
            return null;
        }

        @Override
        public ChatResult<? extends ChatResponse<? extends Openai>> chat(ChatRequest chatRequest) {
            throw new UnsupportedOperationException("本用例只走流式");
        }

        @Override
        public ChatStream<OpenaiChatChunkResponse, OpenaiChatChunkResponse.ToolCallDelta> stream(ChatRequest request) {
            requests.add(request);
            String payload = index < rounds.size() ? rounds.get(index++) : "";
            return ToolLoopSupport.wrap(new OpenaiChatStream(bytes(payload)), request, this, EXTRACTOR);
        }
    }

    // ==================== 装配辅助 ====================

    private static ChatRequest requestWithTools(String userText) {
        return ChatRequest.builder()
                .model("gpt-4o")
                .messages(List.<Message>of(new HumanMessage(userText)))
                .tools(List.of(ChatRequest.Tool.builder()
                        .function(ChatRequest.Tool.Function.builder()
                                .name("get_sys_time")
                                .description("获取系统当前时间")
                                .build())
                        .build()))
                .build();
    }

    private FunctionRegistry registryFor(Object bean) {
        FunctionRegistry registry = new FunctionRegistry();
        AgentFunctionProcessor processor = new AgentFunctionProcessor(registry);
        try {
            processor.process(bean, BeanDefinition.builder()
                    .beanName(bean.getClass().getSimpleName())
                    .beanClass(bean.getClass())
                    .lifecycle(Lifecycle.SINGLETON)
                    .lazyInit(false)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return registry;
    }

    private static InputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String sseChunk(String json) {
        return "data: " + json.replaceAll("\\s*\\n\\s*", " ").trim();
    }

    /** 测试工具：函数名与用户场景一致（{@code @AgentFunction(name = "get_sys_time")}） */
    public static class SystemToolStub {

        @AgentFunction(name = "get_sys_time", description = "获取系统当前时间")
        public String getSystemTime() {
            return "2026:10:12 10:00:00";
        }

        @AgentFunction(name = "boom", description = "必炸工具")
        public String boom() {
            throw new IllegalStateException("工具内部炸了");
        }
    }
}
