package com.taixu.tiangong.trace;

import com.taixu.tiangong.common.agent.call.AgentCallContext;
import com.taixu.tiangong.llm.message.HumanMessage;
import com.taixu.tiangong.llm.message.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceObservabilityTest {

    @AfterEach
    void tearDown() {
        TraceRecorder.reset();
    }

    @Test
    void redactorMasksSecretsAndTruncates() {
        String text = "Authorization: Bearer abcdefgh12345678, api_key=sk-supersecret, "
                + "github_pat_abcdefgh12345678, user@example.com";
        String redacted = TextRedactor.redact(text);
        assertFalse(redacted.contains("abcdefgh12345678"));
        assertFalse(redacted.contains("sk-supersecret"));
        assertFalse(redacted.contains("user@example.com"));
        assertTrue(redacted.contains("Bearer ***"));
        assertTrue(redacted.contains("api_key=***"));
        assertEquals("sk-***", TextRedactor.redact("sk-abcdefgh12345"));
        assertTrue(redacted.contains("***@***"));

        String longText = "a".repeat(30);
        assertTrue(TextRedactor.redact(longText, 10).endsWith("…"));
    }

    @Test
    void promptHashIsStableAndContentSensitive() {
        List<Message> first = List.of(new HumanMessage("写周报"));
        List<Message> second = List.of(new HumanMessage("写日报"));
        assertEquals(PromptHasher.hash(first), PromptHasher.hash(first));
        assertFalse(PromptHasher.hash(first).equals(PromptHasher.hash(second)));
        assertEquals(64, PromptHasher.hash(first).length());
    }

    @Test
    void recorderStoresRedactedLlmAndToolRecords() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        TraceRecorder.install(store);
        AgentCallContext context = new AgentCallContext("u-1", "tenant-1", Set.of(), "trace",
                Map.of("runId", "run-1", "turnId", "turn-1", "node", "report"));
        List<Message> messages = List.of(new HumanMessage("请总结，token=sk-supersecret"));

        LlmTraceRecord llm = AgentCallContext.callWith(context, () ->
                TraceRecorder.recordLlm("deepseek-flash", messages,
                        "结果 github_pat_abcdefgh12345678", 10, 20, 30, 5, "USD",
                        12, "success", null));
        ToolTraceRecord tool = AgentCallContext.callWith(context, () ->
                TraceRecorder.recordTool("draft_report", "call-1",
                        "{\"text\":\"secret=abc\"}", "ok", "success", 3, null));

        assertEquals(2, store.size());
        assertEquals("run-1", llm.runId());
        assertEquals("turn-1", llm.turnId());
        assertEquals("report", llm.node());
        assertEquals(PromptHasher.hash(messages), llm.promptHash());
        assertTrue(llm.promptPreview().contains("token=***"));
        assertFalse(llm.promptPreview().contains("supersecret"));
        assertTrue(llm.resultPreview().contains("github_pat_***"));
        assertEquals(30, llm.totalTokens());
        assertTrue(store.findByPromptHash("run-1", llm.promptHash()).isPresent());

        assertEquals("draft_report", tool.toolName());
        assertTrue(tool.argumentsPreview().contains("secret=***"));
    }

    @Test
    void replaySessionVerifiesGoldenTraceAndDetectsDrift() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        TraceRecorder.install(store);
        AgentCallContext context = new AgentCallContext("u-1", "tenant-1", Set.of(), "trace",
                Map.of("runId", "run-1", "node", "report"));
        List<Message> messages = List.of(new HumanMessage("写周报"));

        AgentCallContext.callWith(context, () -> {
            TraceRecorder.recordLlm("deepseek", messages, "周报正文", 1, 2, 3, 0, "USD",
                    5, "success", null);
            TraceRecorder.recordTool("draft_report", "call-1", "{}", "ok", "success", 1, null);
            return null;
        });

        ReplaySession session = new ReplaySession(store, "run-1");
        LlmTraceRecord llm = session.expectLlm(messages);
        session.verifyResult(llm, "周报正文");
        ToolTraceRecord tool = session.expectTool("draft_report");
        session.verifyToolResult(tool, "ok");
        session.assertExhausted();

        ReplaySession drifted = new ReplaySession(store, "run-1");
        assertThrows(ReplayMismatchException.class,
                () -> drifted.expectLlm(List.of(new HumanMessage("写日报"))));
    }
}
