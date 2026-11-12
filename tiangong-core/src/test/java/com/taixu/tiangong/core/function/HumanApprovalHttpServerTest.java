package com.taixu.tiangong.core.function;

import com.taixu.tiangong.common.agent.call.AgentCallContext;
import com.taixu.tiangong.common.agent.tool.ToolApprovalDecision;
import com.taixu.tiangong.common.agent.tool.ToolApprovalRequest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HumanApprovalHttpServerTest {

    @Test
    void listAndApproveOverHttp() throws Exception {
        AgentCallContext context = new AgentCallContext("u", "tenant", Set.of(), "trace");
        try (HumanApprovalService service = new HumanApprovalService(Duration.ofSeconds(3));
             HumanApprovalHttpServer server = new HumanApprovalHttpServer(0, service, "secret")) {
            server.start();
            CompletableFuture<ToolApprovalDecision> decision = CompletableFuture.supplyAsync(
                    () -> service.decide(new ToolApprovalRequest("publish", Map.of(), context)));

            HumanApprovalService.PendingApproval pending = awaitPending(service);
            String base = "http://127.0.0.1:" + server.getPort() + "/approvals";

            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> unauthorized = client.send(
                    HttpRequest.newBuilder(URI.create(base)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauthorized.statusCode());

            HttpResponse<String> list = client.send(
                    HttpRequest.newBuilder(URI.create(base))
                            .header("Authorization", "Bearer secret").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, list.statusCode());
            assertTrue(list.body().contains(pending.id()));

            HttpResponse<String> approve = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/" + pending.id() + "/approve"))
                            .header("Authorization", "Bearer secret")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"approver\":\"alice\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, approve.statusCode());
            assertEquals(ToolApprovalDecision.APPROVED, decision.get(1, TimeUnit.SECONDS));
        }
    }

    private static HumanApprovalService.PendingApproval awaitPending(HumanApprovalService service)
            throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            if (!service.pending().isEmpty()) {
                return service.pending().getFirst();
            }
            Thread.sleep(5);
        }
        throw new AssertionError("审批 ticket 未出现");
    }
}
