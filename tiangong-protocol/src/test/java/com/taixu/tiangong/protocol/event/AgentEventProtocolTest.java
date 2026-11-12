package com.taixu.tiangong.protocol.event;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEventProtocolTest {

    @Test
    void codecRoundTripKeepsTypeAndData() {
        AgentEventJournal journal = new AgentEventJournal("run-1", "turn-1");
        AgentEventEnvelope envelope = journal.append(new AgentEvent.MessageDelta("你好"));
        assertNotNull(envelope);
        assertEquals(1, envelope.seq());
        assertEquals("message.delta", envelope.eventName());

        AgentEventEnvelope decoded = AgentEventCodec.fromJson(AgentEventCodec.toJsonString(envelope));
        assertNotNull(decoded);
        assertEquals(envelope.seq(), decoded.seq());
        assertEquals(envelope.runId(), decoded.runId());
        assertEquals(envelope.turnId(), decoded.turnId());
        assertEquals(new AgentEvent.MessageDelta("你好"), decoded.event());
    }

    @Test
    void codecHandlesStructuredAndToolEvents() {
        AgentEventJournal journal = new AgentEventJournal("r", "t");
        AgentEventEnvelope structured = journal.append(
                new AgentEvent.Structured("ReportDraft", java.util.Map.of("title", "周报")));
        AgentEventEnvelope tool = journal.append(
                new AgentEvent.ToolResult("call-1", "draft_report", "success", "{}", 12));

        AgentEvent.Structured decodedStructured =
                (AgentEvent.Structured) AgentEventCodec.fromJson(AgentEventCodec.toJsonString(structured)).event();
        assertEquals("ReportDraft", decodedStructured.schemaName());
        assertEquals("周报", decodedStructured.data().get("title"));

        AgentEvent.ToolResult decodedTool =
                (AgentEvent.ToolResult) AgentEventCodec.fromJson(AgentEventCodec.toJsonString(tool)).event();
        assertEquals("call-1", decodedTool.callId());
        assertEquals(12, decodedTool.durationMs());
    }

    @Test
    void journalAssignsMonotonicSeqAndDropsAfterDone() {
        AgentEventJournal journal = new AgentEventJournal("run", "turn");
        assertEquals(1, journal.append(new AgentEvent.MessageDelta("a")).seq());
        assertEquals(2, journal.append(new AgentEvent.MessageDelta("b")).seq());
        journal.complete("completed");
        assertTrue(journal.isTerminal());
        assertNull(journal.append(new AgentEvent.MessageDelta("late")));
        assertEquals(3, journal.size());
    }

    @Test
    void journalReplaysOnlyEventsAfterLastId() {
        AgentEventJournal journal = new AgentEventJournal("run", "turn");
        journal.append(new AgentEvent.MessageDelta("a"));
        journal.append(new AgentEvent.MessageDelta("b"));
        journal.append(new AgentEvent.MessageDelta("c"));

        List<AgentEventEnvelope> replay = journal.replayAfter(1);
        assertEquals(2, replay.size());
        assertEquals(2, replay.get(0).seq());
        assertEquals(3, replay.get(1).seq());
    }

    @Test
    void journalSubscribeReplaysThenReceivesLiveEvents() {
        AgentEventJournal journal = new AgentEventJournal("run", "turn");
        journal.append(new AgentEvent.MessageDelta("old"));
        List<AgentEventEnvelope> received = new ArrayList<>();
        try (AgentEventJournal.Subscription ignored = journal.subscribe(received::add, 0)) {
            journal.append(new AgentEvent.MessageDelta("new"));
        }
        assertEquals(2, received.size());
        assertEquals("old", ((AgentEvent.MessageDelta) received.get(0).event()).delta());
        assertEquals("new", ((AgentEvent.MessageDelta) received.get(1).event()).delta());
    }

    @Test
    void cancelInvokesHookAndTerminatesWithCancelled() {
        AgentEventJournal journal = new AgentEventJournal("run", "turn");
        boolean[] cancelled = {false};
        journal.onCancel(() -> cancelled[0] = true);
        assertTrue(journal.cancel());
        assertTrue(cancelled[0]);
        assertTrue(journal.isTerminal());
        AgentEventEnvelope last = journal.all().getLast();
        assertTrue(last.event() instanceof AgentEvent.Done);
        assertEquals("cancelled", ((AgentEvent.Done) last.event()).status());
    }
}
