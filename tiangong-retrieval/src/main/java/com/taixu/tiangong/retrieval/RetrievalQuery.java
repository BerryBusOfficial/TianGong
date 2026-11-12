package com.taixu.tiangong.retrieval;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次检索请求。<br>
 * <br>
 * 只包含「检索什么」；<b>权限不在这里</b> —— 身份在 {@code AgentCallContext}，ACL 在
 * {@link DocumentAcl}。把 tenant/user 塞进 query 会让每个 retriever 各写一遍权限判断，
 * 那正是本 SPI 要消灭的重复。
 *
 * @param text    查询文本
 * @param topK    期望返回条数（≤0 时取默认 5）
 * @param filters 交给具体向量库/搜索后端的过滤条件（可为空）
 */
public record RetrievalQuery(String text, int topK, Map<String, Object> filters) {

    public static final int DEFAULT_TOP_K = 5;

    public RetrievalQuery {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("检索文本不能为空");
        }
        if (topK <= 0) {
            topK = DEFAULT_TOP_K;
        }
        filters = filters == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(filters));
    }

    public RetrievalQuery(String text) {
        this(text, DEFAULT_TOP_K, Map.of());
    }

    public static RetrievalQuery of(String text, int topK) {
        return new RetrievalQuery(text, topK, Map.of());
    }
}
