package com.taixu.tiangong.protocol.sse;

import com.taixu.tiangong.protocol.event.AgentEvent;
import com.taixu.tiangong.protocol.event.AgentEventEnvelope;
import com.taixu.tiangong.protocol.event.AgentEventJournal;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEventSseCodecTest {

    @Test
    void writerAndReaderRoundTrip() throws Exception {
        AgentEventJournal journal = new AgentEventJournal("run-1", "turn-1");
        journal.append(new AgentEvent.ReasoningDelta("想"));
        journal.append(new AgentEvent.MessageDelta("答"));
        journal.append(new AgentEvent.Usage(10, 20, 30, "deepseek-chat"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (AgentEventEnvelope envelope : journal.all()) {
            AgentEventSseWriter.write(out, envelope);
        }

        List<AgentEventEnvelope> parsed = new ArrayList<>();
        long lastId = AgentEventSseReader.read(new ByteArrayInputStream(out.toByteArray()), parsed::add);
        assertEquals(3, parsed.size());
        assertEquals(3, lastId);
        assertEquals(new AgentEvent.ReasoningDelta("想"), parsed.get(0).event());
        assertEquals(new AgentEvent.MessageDelta("答"), parsed.get(1).event());
        assertEquals(new AgentEvent.Usage(10, 20, 30, "deepseek-chat"), parsed.get(2).event());
    }

    @Test
    void readerSkipsCommentsHeartbeatsAndUnknownEvents() throws Exception {
        String stream = """
                : ping

                id: 1
                event: message.delta
                data: {"seq":1,"runId":"r","turnId":"t","timestamp":0,"type":"message.delta","data":{"delta":"ok"}}

                id: 2
                event: future.event
                data: {"seq":2,"runId":"r","turnId":"t","timestamp":0,"type":"future.event","data":{}}

                """;
        List<AgentEventEnvelope> parsed = new ArrayList<>();
        AgentEventSseReader.read(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)), parsed::add);
        assertEquals(1, parsed.size());
        assertEquals("ok", ((AgentEvent.MessageDelta) parsed.getFirst().event()).delta());
    }

    @Test
    void readerReturnsNullForEmptyStream() throws Exception {
        assertNull(AgentEventSseReader.next(new java.io.BufferedReader(new java.io.StringReader(""))));
    }

    @Test
    void multiLineDataIsJoined() throws Exception {
        // 事件 JSON 一般单行，但 SSE 允许 data 分多行；这里验证分帧器不会把它们当成两条事件
        String stream = """
                id: 1
                event: message.delta
                data: {"seq":1,"runId":"r","turnId":"t","timestamp":0,"type":"message.delta",
                data: "data":{"delta":"ok"}}

                """;
        List<AgentEventEnvelope> parsed = new ArrayList<>();
        AgentEventSseReader.read(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)), parsed::add);
        assertEquals(1, parsed.size());
        assertTrue(parsed.getFirst().event() instanceof AgentEvent.MessageDelta);
    }
}
