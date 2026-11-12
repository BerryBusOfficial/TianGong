package org.wutong.tiangong.test;

import org.junit.jupiter.api.Test;
import org.wutong.tiangong.llm.api.request.chat.ChatRequest;
import org.wutong.tiangong.llm.api.request.chat.OllamaChatRequest;
import org.wutong.tiangong.llm.api.response.chat.OllamaChatResponse;
import org.wutong.tiangong.llm.client.OllamaLlmClient;
import org.wutong.tiangong.llm.entity.Option;
import org.wutong.tiangong.llm.event.result.OllamaChatResult;
import org.wutong.tiangong.llm.event.stream.OllamaChatStream;
import org.wutong.tiangong.llm.message.AssistantMessage;
import org.wutong.tiangong.llm.message.HumanMessage;
import org.wutong.tiangong.llm.message.SystemMessage;
import org.wutong.tiangong.llm.options.chat.OllamaChatOption;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LLM 集成测试：需要本地 Ollama 服务（localhost:11434）且已拉取 qwen3:8b 模型。<br>
 * 覆盖：同步 chat、流式 stream、消息类型、多轮对话。
 */
class LlmIntegrationTest {

    private static final String MODEL = "qwen3:8b";
    private static final String BASE_URL = "http://localhost:11434";
    private final OllamaLlmClient client = new OllamaLlmClient(BASE_URL, "ollama");

    @Test
    void syncChatReturnsResponse() {
        OllamaChatResult result = client.chat(ChatRequest.builder()
                .model(MODEL)
                .messages(List.of(new HumanMessage("你好，请回复一个字")))
                .stream(false)
                .chatOptions(OllamaChatOption.builder()
                        .think(new OllamaChatRequest.Think(false))
                        .options(Option.builder().temperature(0.7).build())
                        .build())
                .build());

        OllamaChatResponse response = result.join();
        assertNotNull(response);
        assertNotNull(response.message());
        assertFalse(response.message().content().isEmpty());
    }

    @Test
    void streamChatReturnsData() {
        OllamaChatStream stream = client.stream(ChatRequest.builder()
                .model(MODEL)
                .messages(List.of(new HumanMessage("说一个数字")))
                .stream(true)
                .chatOptions(OllamaChatOption.builder()
                        .think(new OllamaChatRequest.Think(false))
                        .build())
                .build());

        stream.start().await();

        var result = stream.result().join();
        assertNotNull(result, "流式聚合结果不应为 null");
        assertFalse(result.content().isEmpty(), "流式响应内容不应为空");
    }

    @Test
    void systemMessageGuidesResponse() {
        OllamaChatResult result = client.chat(ChatRequest.builder()
                .model(MODEL)
                .messages(List.of(
                        new SystemMessage("你只能回复'是的'两个字，不能说其他任何话"),
                        new HumanMessage("今天天气好吗？")
                ))
                .stream(false)
                .chatOptions(OllamaChatOption.builder()
                        .think(new OllamaChatRequest.Think(false))
                        .build())
                .build());

        OllamaChatResponse response = result.join();
        assertNotNull(response);
        assertNotNull(response.message());
    }

    @Test
    void multiTurnConversation() {
        // 第一轮
        OllamaChatResult r1 = client.chat(ChatRequest.builder()
                .model(MODEL)
                .messages(List.of(new HumanMessage("记住数字42")))
                .stream(false)
                .chatOptions(OllamaChatOption.builder()
                        .think(new OllamaChatRequest.Think(false))
                        .build())
                .build());
        OllamaChatResponse resp1 = r1.join();
        assertNotNull(resp1);

        // 第二轮：追问刚才的数字
        OllamaChatResult r2 = client.chat(ChatRequest.builder()
                .model(MODEL)
                .messages(List.of(
                        new HumanMessage("记住数字42"),
                        new AssistantMessage(resp1.message().content()),
                        new HumanMessage("我刚才让你记住的数字是什么？")
                ))
                .stream(false)
                .chatOptions(OllamaChatOption.builder()
                        .think(new OllamaChatRequest.Think(false))
                        .build())
                .build());
        OllamaChatResponse resp2 = r2.join();
        assertNotNull(resp2);
        assertTrue(resp2.message().content().contains("42"),
                "第二轮应能回忆第一轮的数字 42");
    }
}
