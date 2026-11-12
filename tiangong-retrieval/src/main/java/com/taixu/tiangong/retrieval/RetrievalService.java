package com.taixu.tiangong.retrieval;

import com.taixu.tiangong.common.agent.call.AgentCallContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索服务：召回 → ACL 裁剪 → 排序/截断 → 发 citation 事件 → 拼提示词上下文。<br>
 * <br>
 * 这是「图扩散 RAG 可插拔」的落点：换 retriever 不改业务，权限与引用口径始终由本类收口。
 * 多 retriever 查询按 docId 合并（保留更高 score），让「向量召回 + 图扩散 + 记忆事实」
 * 可以一次进同一份上下文。
 */
public final class RetrievalService {

    private final RetrieverRegistry registry;
    private final DocumentAcl acl;
    private final CitationCollector citations;

    public RetrievalService(RetrieverRegistry registry) {
        this(registry, DocumentAcl.scopeBased(), new CitationCollector());
    }

    public RetrievalService(RetrieverRegistry registry, DocumentAcl acl) {
        this(registry, acl, new CitationCollector());
    }

    public RetrievalService(RetrieverRegistry registry, DocumentAcl acl, CitationCollector citations) {
        this.registry = registry;
        this.acl = acl == null ? DocumentAcl.scopeBased() : acl;
        this.citations = citations == null ? new CitationCollector() : citations;
    }

    public List<RetrievedDocument> retrieve(String retrieverName, RetrievalQuery query) {
        return retrieve(retrieverName, query, AgentCallContext.current());
    }

    /**
     * 执行一次检索。<br>
     * ACL 裁剪发生在结果进入提示词之前；被裁掉的文档不会发 citation，也不会进入 contextBlock。
     */
    public List<RetrievedDocument> retrieve(String retrieverName, RetrievalQuery query,
                                            AgentCallContext context) {
        if (query == null) {
            throw new IllegalArgumentException("RetrievalQuery 不能为空");
        }
        return finalize(recall(retrieverName, query, context), query, context);
    }

    /**
     * 多检索器合并查询：按 docId 去重，保留更高 score，再做统一的 ACL/排序/截断/引用。<br>
     * 任一 retriever 失败都会抛出带其名字的异常 —— 静默吞掉会让「图扩散没跑」看起来像
     * 「没有相关文档」，是最难排查的 RAG 故障。
     */
    public List<RetrievedDocument> retrieve(List<String> retrieverNames, RetrievalQuery query,
                                            AgentCallContext context) {
        if (query == null) {
            throw new IllegalArgumentException("RetrievalQuery 不能为空");
        }
        if (retrieverNames == null || retrieverNames.isEmpty()) {
            return List.of();
        }
        Map<String, RetrievedDocument> merged = new LinkedHashMap<>();
        for (String name : retrieverNames) {
            for (RetrievedDocument document : recall(name, query, context)) {
                if (document == null) {
                    continue;
                }
                merged.merge(document.docId(), document,
                        (left, right) -> left.score() >= right.score() ? left : right);
            }
        }
        return finalize(new ArrayList<>(merged.values()), query, context);
    }

    private List<RetrievedDocument> recall(String retrieverName, RetrievalQuery query,
                                          AgentCallContext context) {
        Retriever retriever = registry.require(retrieverName);
        try {
            List<RetrievedDocument> raw = retriever.retrieve(query, context);
            return raw == null ? List.of() : raw;
        } catch (RetrievalException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RetrievalException(
                    "Retriever [" + retrieverName + "] 执行失败: " + e.getMessage(), e);
        }
    }

    private List<RetrievedDocument> finalize(List<RetrievedDocument> raw, RetrievalQuery query,
                                             AgentCallContext context) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<RetrievedDocument> allowed = raw.stream()
                .filter(document -> document != null && acl.canRead(document, context))
                .sorted(Comparator.comparingDouble(RetrievedDocument::score).reversed())
                .limit(query.topK())
                .toList();
        citations.collect(allowed);
        return allowed;
    }

    /**
     * 把检索结果拼成模型可用的上下文块，带编号供回答里引用。<br>
     * 不在这里做「请引用 [1]」的指令 —— 提示词风格属于业务，框架只提供稳定的数据形态。
     */
    public static String toPromptBlock(List<RetrievedDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return "";
        }
        StringBuilder block = new StringBuilder();
        int index = 1;
        for (RetrievedDocument document : documents) {
            if (document == null) {
                continue;
            }
            block.append('[').append(index++).append("] ").append(document.title()).append('\n');
            if (!document.url().isBlank()) {
                block.append("    来源: ").append(document.url()).append('\n');
            }
            block.append("    文档: ").append(document.docId()).append('\n');
            block.append("    摘要: ").append(document.snippet(360)).append('\n');
        }
        return block.toString();
    }

    public CitationCollector citations() {
        return citations;
    }

    /** 新一次运行前清空引用去重表 */
    public void resetCitations() {
        citations.reset();
    }
}
