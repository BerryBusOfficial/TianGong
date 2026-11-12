package com.taixu.tiangong.retrieval;

import com.taixu.tiangong.common.agent.call.AgentCallContext;
import com.taixu.tiangong.protocol.event.AgentEvent;
import com.taixu.tiangong.protocol.event.AgentEventEnvelope;
import com.taixu.tiangong.protocol.event.AgentEventJournal;
import com.taixu.tiangong.protocol.event.AgentEventScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalServiceTest {

    private static final AgentCallContext CONTEXT = new AgentCallContext(
            "u-1", "tenant-a", Set.of("report:read"), "trace-1");

    @Test
    void aclFiltersTenantAndScopeBeforePrompt() {
        AgentEventJournal journal = new AgentEventJournal("run", "turn");
        AgentEventScope.Binding binding = new AgentEventScope.Binding(journal, "run", "turn");

        List<RetrievedDocument> docs = List.of(
                RetrievedDocument.of("public-1", "公开说明", "所有人都能看", 0.5),
                RetrievedDocument.scoped("report-1", "周报规则", "report:read 可见", 0.9,
                        Set.of("report:read")),
                new RetrievedDocument("tenant-b-1", "别家租户", "", "tenant-b 的内容", 0.99,
                        Set.of(), Map.of("tenantId", "tenant-b")),
                RetrievedDocument.scoped("admin-1", "管理员配置", "admin:read 可见", 0.8,
                        Set.of("admin:read")));

        RetrievalService service = new RetrievalService(registry(docs));
        AtomicReference<List<RetrievedDocument>> result = new AtomicReference<>();
        AgentEventScope.runWith(binding, () ->
                result.set(service.retrieve("test", new RetrievalQuery("周报", 5, Map.of()), CONTEXT)));

        assertEquals(List.of("report-1", "public-1"),
                result.get().stream().map(RetrievedDocument::docId).toList());
        List<AgentEventEnvelope> events = journal.all();
        assertEquals(2, events.size());
        assertTrue(events.stream().allMatch(e -> e.event() instanceof AgentEvent.Citation));
        assertEquals("report-1", ((AgentEvent.Citation) events.getFirst().event()).docId());
    }

    @Test
    void citationsAreDeduplicatedAcrossCalls() {
        AgentEventJournal journal = new AgentEventJournal("run", "turn");
        AgentEventScope.Binding binding = new AgentEventScope.Binding(journal, "run", "turn");
        RetrievalService service = new RetrievalService(registry(List.of(
                RetrievedDocument.of("doc-1", "标题", "正文", 0.7))));

        AgentEventScope.runWith(binding, () -> {
            service.retrieve("test", new RetrievalQuery("x"), CONTEXT);
            service.retrieve("test", new RetrievalQuery("x"), CONTEXT);
        });

        assertEquals(1, journal.all().size());
        assertEquals(1, service.citations().size());
    }

    @Test
    void noContextAllowsPublicButDeniesScoped() {
        RetrievalService service = new RetrievalService(registry(List.of(
                RetrievedDocument.of("public-1", "公开", "ok", 0.5),
                RetrievedDocument.scoped("private-1", "私有", "secret", 0.9, Set.of("report:read")))));

        List<RetrievedDocument> result = service.retrieve("test", new RetrievalQuery("x"), null);
        assertEquals(List.of("public-1"), result.stream().map(RetrievedDocument::docId).toList());
    }

    @Test
    void topKLimitsAndSortsByScore() {
        RetrievalService service = new RetrievalService(registry(List.of(
                RetrievedDocument.of("a", "a", "a", 0.1),
                RetrievedDocument.of("b", "b", "b", 0.9),
                RetrievedDocument.of("c", "c", "c", 0.5))));

        List<RetrievedDocument> result = service.retrieve("test",
                new RetrievalQuery("x", 2, Map.of()), CONTEXT);
        assertEquals(List.of("b", "c"), result.stream().map(RetrievedDocument::docId).toList());
    }

    @Test
    void unknownRetrieverGivesActionableError() {
        RetrievalService service = new RetrievalService(new RetrieverRegistry());
        RetrievalException error = assertThrows(RetrievalException.class,
                () -> service.retrieve("missing", new RetrievalQuery("x"), CONTEXT));
        assertTrue(error.getMessage().contains("missing"));
        assertTrue(error.getMessage().contains("已注册"));
    }

    @Test
    void promptBlockCarriesCitationMarkers() {
        String block = RetrievalService.toPromptBlock(List.of(
                RetrievedDocument.of("doc-1", "周报规则", "第一段正文", 0.8)));
        assertTrue(block.contains("[1] 周报规则"));
        assertTrue(block.contains("文档: doc-1"));
    }

    @Test
    void multiRetrieverMergesByDocIdAndKeepsHigherScore() {
        RetrieverRegistry registry = new RetrieverRegistry();
        registry.register(new InMemoryRetriever("vector", List.of(
                RetrievedDocument.of("shared", "共享", "向量片段", 0.6),
                RetrievedDocument.of("vector-only", "向量独有", "v", 0.4))));
        registry.register(new InMemoryRetriever("graph", List.of(
                RetrievedDocument.of("shared", "共享", "图片段", 0.9),
                RetrievedDocument.of("graph-only", "图独有", "g", 0.5))));

        RetrievalService service = new RetrievalService(registry);
        List<RetrievedDocument> result = service.retrieve(
                List.of("vector", "graph"), new RetrievalQuery("x", 2, Map.of()), CONTEXT);

        assertEquals(List.of("shared", "graph-only"),
                result.stream().map(RetrievedDocument::docId).toList());
        assertEquals("图片段", result.getFirst().content());
    }

    private static RetrieverRegistry registry(List<RetrievedDocument> docs) {
        RetrieverRegistry registry = new RetrieverRegistry();
        registry.register(new InMemoryRetriever("test", docs));
        return registry;
    }

    static final class InMemoryRetriever implements Retriever {

        private final String name;
        private final List<RetrievedDocument> docs;

        InMemoryRetriever(String name, List<RetrievedDocument> docs) {
            this.name = name;
            this.docs = docs;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public List<RetrievedDocument> retrieve(RetrievalQuery query, AgentCallContext context) {
            return docs;
        }
    }
}
