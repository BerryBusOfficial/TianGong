package com.taixu.tiangong.core.llm;

import com.taixu.tiangong.common.type.impl.Openai;
import com.taixu.tiangong.llm.api.Api;
import com.taixu.tiangong.llm.api.request.chat.ChatRequest;
import com.taixu.tiangong.llm.api.response.chat.OpenaiChatResponse;
import com.taixu.tiangong.llm.api.response.chat.chunk.ChatChunkResponse;
import com.taixu.tiangong.llm.client.LlmClient;
import com.taixu.tiangong.llm.event.result.ChatResult;
import com.taixu.tiangong.llm.event.stream.ChatStream;
import com.taixu.tiangong.llm.message.HumanMessage;
import com.taixu.tiangong.llm.provider.OpenaiProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredOutputTest {

    private static final StructuredOutput SPEC = StructuredOutput.of(ReportDraft.class);

    @Test
    void schemaDescribesNestedRecordAndOptionalField() {
        Map<String, Object> schema = SPEC.schema();
        assertEquals("object", schema.get("type"));
        assertEquals(Boolean.FALSE, schema.get("additionalProperties"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> title = (Map<String, Object>) properties.get("title");
        assertEquals("string", title.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> points = (Map<String, Object>) properties.get("points");
        assertEquals("array", points.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) points.get("items");
        assertEquals("string", items.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> priority = (Map<String, Object>) properties.get("priority");
        assertEquals(List.of("HIGH", "LOW"), priority.get("enum"));

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertTrue(required.contains("title"));
        assertTrue(required.contains("points"));
        assertTrue(required.contains("priority"));
        assertFalse(required.contains("note"));
    }

    @Test
    void parserAcceptsFenceAndProseButRejectsMissingField() {
        String valid = """
                下面是周报草稿：
                ```json
                {"title":"周报","points":["A","B"],"priority":"HIGH"}
                ```
                """;
        ReportDraft draft = StructuredOutputParser.parse(valid, SPEC);
        assertEquals("周报", draft.title());
        assertEquals(List.of("A", "B"), draft.points());
        assertEquals(Priority.HIGH, draft.priority());
        assertEquals(Optional.empty(), draft.note());

        StructuredOutputException missing = assertThrows(StructuredOutputException.class,
                () -> StructuredOutputParser.parse(
                        "{\"title\":\"周报\",\"priority\":\"HIGH\"}", SPEC));
        assertTrue(missing.errors().stream().anyMatch(e -> e.contains("points")));
    }

    @Test
    void parserRejectsExtraFields() {
        StructuredOutputException extra = assertThrows(StructuredOutputException.class,
                () -> StructuredOutputParser.parse(
                        "{\"title\":\"周报\",\"points\":[],\"priority\":\"HIGH\",\"secret\":1}", SPEC));
        assertTrue(extra.errors().stream().anyMatch(e -> e.contains("secret")));
    }

    @Test
    void extractorHandlesBracesInsideStringsAndChunkBoundaries() {
        String json = "{\"title\":\"a}b\",\"points\":[\"x\"],\"priority\":\"HIGH\"}";
        assertEquals(json, StructuredOutputParser.extractJson("前缀 " + json + " 后缀"));

        StreamingJsonExtractor extractor = new StreamingJsonExtractor();
        extractor.accept("前缀 ```json {\"title\":\"a}b\",");
        assertTrue(extractor.tryExtract().isEmpty());
        extractor.accept("\"points\":[\"x\"],\"priority\":\"HIGH\"} ``` 后缀");
        String extracted = extractor.tryExtract().orElseThrow();
        assertEquals(json, extracted);
    }

    @Test
    void runnerRepairsOnceWithSchemaErrors() {
        FakeClient client = new FakeClient();
        ChatRequest request = ChatRequest.builder()
                .model("gpt-test")
                .messages(List.of(new HumanMessage("写周报")))
                .build();

        ReportDraft draft = StructuredOutputRunner.run(client, request, SPEC);

        assertEquals("周报", draft.title());
        assertEquals(2, client.calls);
        assertEquals(ChatRequest.ResponseFormatType.JSON_SCHEMA,
                client.requests.getFirst().getResponseFormat().type());
        // 第二次请求应带上「上次输出哪里错了」的修复提示
        boolean hasRepairPrompt = client.requests.get(1).getMessages().stream()
                .anyMatch(message -> message.getContent() != null
                        && message.getContent().contains("不符合约定"));
        assertTrue(hasRepairPrompt);
    }

    record ReportDraft(String title, List<String> points, Priority priority, Optional<String> note) {
    }

    enum Priority {
        HIGH,
        LOW
    }

    /**
     * 只实现 runner 用到的 {@code chat}；其余协议方法保持 null/空实现。
     */
    static final class FakeClient implements LlmClient<Openai, OpenaiProvider> {

        private final List<ChatRequest> requests = new ArrayList<>();
        private int calls;

        @Override
        public OpenaiProvider getProvider() {
            return null;
        }

        @Override
        public Api getApi() {
            return null;
        }

        @Override
        public ChatResult<OpenaiChatResponse> chat(ChatRequest chatRequest) {
            requests.add(chatRequest);
            calls++;
            String content = calls == 1
                    ? "{\"title\":\"周报\"}"
                    : "{\"title\":\"周报\",\"points\":[\"A\"],\"priority\":\"HIGH\"}";
            OpenaiChatResponse response = new OpenaiChatResponse(
                    "id", "chat.completion", 0, "gpt-test",
                    List.of(new OpenaiChatResponse.Choice(0,
                            new OpenaiChatResponse.ResponseMessage("assistant", content,
                                    null, null, null, null),
                            null, "stop")),
                    null,
                    new OpenaiChatResponse.Usage(1, 1, 2, null, null),
                    null);
            return new ChatResult<>() {
                @Override
                public ChatResult<OpenaiChatResponse> onData(Consumer<? super OpenaiChatResponse> consumer) {
                    consumer.accept(response);
                    return this;
                }

                @Override
                public ChatResult<OpenaiChatResponse> onComplete(Runnable consumer) {
                    consumer.run();
                    return this;
                }

                @Override
                public ChatResult<OpenaiChatResponse> onError(Consumer<? super Throwable> consumer) {
                    return this;
                }

                @Override
                public OpenaiChatResponse join() {
                    return response;
                }

                @Override
                public void cancel() {
                }
            };
        }

        @Override
        public ChatStream<? extends ChatChunkResponse<? extends Openai>, ?> stream(ChatRequest request) {
            return null;
        }
    }
}
