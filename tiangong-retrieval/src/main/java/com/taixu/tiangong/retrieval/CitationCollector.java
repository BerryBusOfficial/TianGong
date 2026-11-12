package com.taixu.tiangong.retrieval;

import com.taixu.tiangong.protocol.event.AgentEvent;
import com.taixu.tiangong.protocol.event.AgentEventScope;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 引用收集器：把检索结果转成统一的 {@code citation} 事件，并按 docId 去重。<br>
 * <br>
 * 去重是刻意的：同一次运行里「图扩散」和「向量召回」可能命中同一篇文档，前端不该收到
 * 两条一模一样的引用。去重键用稳定 {@code docId}，不用标题/URL（它们可能变）。
 * <br>
 * <b>生命周期</b>：一次 Agent 运行一个实例（或调用 {@link #reset()}），不要跨用户复用，
 * 否则第二个用户的引用会被第一个用户“吃掉”。
 */
public final class CitationCollector {

    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    /**
     * 收集并发出 citation 事件（当前线程有事件作用域时）。
     *
     * @return 本次新产生的引用（已去重）
     */
    public List<AgentEvent.Citation> collect(List<RetrievedDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        List<AgentEvent.Citation> citations = new ArrayList<>();
        AgentEventScope.Binding binding = AgentEventScope.capture();
        for (RetrievedDocument document : documents) {
            if (document == null || !seen.add(document.docId())) {
                continue;
            }
            AgentEvent.Citation citation = new AgentEvent.Citation(
                    document.docId(),
                    document.title(),
                    document.url(),
                    document.score(),
                    document.snippet());
            citations.add(citation);
            if (binding != null) {
                AgentEventScope.emit(binding, citation);
            }
        }
        return citations;
    }

    public int size() {
        return seen.size();
    }

    public void reset() {
        seen.clear();
    }
}
