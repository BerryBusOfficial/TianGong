package com.taixu.tiangong.retrieval;

import com.taixu.tiangong.common.agent.call.AgentCallContext;

import java.util.List;

/**
 * 检索/记忆的统一契约。<br>
 * <br>
 * 实现方只负责「召回」：向量库、全文索引、图数据库、外部搜索 API 都行。<b>不要在
 * 实现里再写一套 userId/tenant 判断</b> —— 框架的 {@link RetrievalService} 会在召回后
 * 统一执行 {@link DocumentAcl}，然后把引用发成 citation 事件。这样「图扩散 RAG」这类
 * 能力可以按 retriever 插拔，而权限与引用口径只有一份。<br>
 * <br>
 * 约定：返回 null 视为空结果；不要抛受检异常（用 {@link RetrievalException}）。
 */
public interface Retriever {

    /** 注册名（如 {@code report-chunks} / {@code memory-facts}） */
    String name();

    /**
     * 召回文档。
     *
     * @param query   查询（文本 + topK + 后端过滤条件）
     * @param context 调用者上下文；无身份请求时为 null。实现方可以据此做<b>召回前</b>的
     *                粗过滤（如按租户分区），但最终 ACL 仍由框架收口
     */
    List<RetrievedDocument> retrieve(RetrievalQuery query, AgentCallContext context);
}
