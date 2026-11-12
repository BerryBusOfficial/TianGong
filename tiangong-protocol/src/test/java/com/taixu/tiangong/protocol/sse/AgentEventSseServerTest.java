package com.taixu.tiangong.protocol.sse;

import com.taixu.tiangong.protocol.event.AgentEvent;
import com.taixu.tiangong.protocol.event.AgentEventEnvelope;
import com.taixu.tiangong.protocol.event.AgentEventJournal;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentEventSseServerTest {

    @Test
    void clientReadsTypedEventsUntilDone() throws Exception {
        AgentEventJournal journal = new AgentEventJournal("run-1", "turn-1");
        journal.append(new AgentEvent.MessageDelta("hello "));
        journal.append(new AgentEvent.MessageDelta("world"));
        journal.append(new AgentEvent.Done("completed"));

        try (AgentEventSseServer server = new AgentEventSseServer(0, request -> new AgentEventSseServer.Session(journal))) {
            server.start();
            AgentEventClient client = new AgentEventClient(
                    URI.create("http://127.0.0.1:" + server.getPort() + "/events"));

            List<AgentEventEnvelope> events = client.readUntilDone();
            assertEquals(3, events.size());
            assertEquals(new AgentEvent.MessageDelta("hello "), events.get(0).event());
            assertEquals("completed", ((AgentEvent.Done) events.get(2).event()).status());
        }
    }

    @Test
    void lastEventIdReplaysOnlyNewerEvents() throws Exception {
        AgentEventJournal journal = new AgentEventJournal("run-1", "turn-1");
        journal.append(new AgentEvent.MessageDelta("one"));
        journal.append(new AgentEvent.MessageDelta("two"));
        journal.append(new AgentEvent.MessageDelta("three"));
        journal.append(new AgentEvent.Done("completed"));

        try (AgentEventSseServer server = new AgentEventSseServer(0, request -> new AgentEventSseServer.Session(journal))) {
            server.start();
            AgentEventClient client = new AgentEventClient(
                    URI.create("http://127.0.0.1:" + server.getPort() + "/events"));

            List<AgentEventEnvelope> events = new ArrayList<>();
            try (AgentEventClient.Connection connection = client.connect("2")) {
                AgentEventEnvelope envelope;
                while ((envelope = connection.next()) != null) {
                    events.add(envelope);
                    if (envelope.event() instanceof AgentEvent.Done) {
                        break;
                    }
                }
            }
            assertEquals(2, events.size());
            assertEquals(3, events.getFirst().seq());
            assertEquals("three", ((AgentEvent.MessageDelta) events.getFirst().event()).delta());
        }
    }
}
