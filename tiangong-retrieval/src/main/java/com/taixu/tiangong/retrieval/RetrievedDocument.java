package com.taixu.tiangong.retrieval;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 检索命中的文档片段。<br>
 * <br>
 * {@code requiredScopes} 与 {@code metadata.tenantId} 是 ACL 的唯一判据来源：
 * <ul>
 *     <li>{@code requiredScopes} 为空 = 公开片段；非空时调用者必须持有全部 scope；</li>
 *     <li>metadata 里若有 {@code tenantId}，调用者租户必须一致。</li>
 * </ul>
 * <b>实现方必须如实标注</b>：把私有文档标成无 scope，等于绕过框架的 ACL —— 检索层只能
 * 校验声明，不能猜测文档的真实密级。
 *
 * @param docId          稳定文档 id（citation 去重键）
 * @param title          标题（可空串）
 * @param url            可访问地址（可空串）
 * @param content        片段正文
 * @param score          相关性分数（越大越相关）
 * @param requiredScopes 读取所需的 scope
 * @param metadata       透传元数据（tenantId / source / page 等）
 */
public record RetrievedDocument(
        String docId,
        String title,
        String url,
        String content,
        double score,
        Set<String> requiredScopes,
        Map<String, Object> metadata
) {

    public RetrievedDocument {
        Objects.requireNonNull(docId, "docId");
        if (docId.isBlank()) {
            throw new IllegalArgumentException("docId 不能为空");
        }
        title = title == null ? "" : title;
        url = url == null ? "" : url;
        content = content == null ? "" : content;
        requiredScopes = requiredScopes == null
                ? Set.of()
                : Set.copyOf(requiredScopes.stream().filter(Objects::nonNull).toList());
        metadata = metadata == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public static RetrievedDocument of(String docId, String title, String content, double score) {
        return new RetrievedDocument(docId, title, "", content, score, Set.of(), Map.of());
    }

    public static RetrievedDocument scoped(String docId, String title, String content,
                                           double score, Set<String> requiredScopes) {
        return new RetrievedDocument(docId, title, "", content, score, requiredScopes, Map.of());
    }

    /** 从 metadata 读租户；没有则 null（表示不按租户隔离） */
    public String tenantId() {
        Object value = metadata.get("tenantId");
        return value == null ? null : String.valueOf(value);
    }

    /** 供 citation / 提示词使用的短摘要：压平空白并截断 */
    public String snippet(int maxChars) {
        String flat = content.replaceAll("\\s+", " ").trim();
        int limit = Math.max(1, maxChars);
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }

    public String snippet() {
        return snippet(240);
    }
}
