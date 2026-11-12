package com.taixu.tiangong.retrieval;

import com.taixu.tiangong.common.agent.call.AgentCallContext;

/**
 * 文档级 ACL：在检索结果进入提示词之前做最后一次权限裁剪。<br>
 * <br>
 * <b>为什么放在检索层而不是提示词层</b>：一旦私有片段进入模型上下文，模型已经“看过”了，
 * 再在输出侧过滤只是掩耳盗铃；ACL 必须在召回结果进入 prompt 前生效。<br>
 * <br>
 * 默认实现 {@link #scopeBased()} 用 {@code requiredScopes} + {@code metadata.tenantId}
 * 两个声明判断；实现方也可以在 retriever 内做召回前粗过滤，但最终一律再过本接口。
 */
@FunctionalInterface
public interface DocumentAcl {

    boolean canRead(RetrievedDocument document, AgentCallContext context);

    static DocumentAcl allowAll() {
        return (document, context) -> true;
    }

    /**
     * 默认 ACL：租户一致 + 持有全部 requiredScopes。<br>
     * 公开片段（无 scope、无 tenantId）对所有人可读；有 scope 但无调用者上下文 → 拒绝。
     */
    static DocumentAcl scopeBased() {
        return (document, context) -> {
            String tenant = document.tenantId();
            if (tenant != null && !tenant.isBlank()) {
                if (context == null || context.tenantId() == null
                        || !tenant.equals(context.tenantId())) {
                    return false;
                }
            }
            if (document.requiredScopes().isEmpty()) {
                return true;
            }
            return context != null && context.hasAllScopes(document.requiredScopes());
        };
    }

    /** 组合：两个 ACL 都通过才可读 */
    default DocumentAcl and(DocumentAcl other) {
        return (document, context) -> canRead(document, context)
                && other.canRead(document, context);
    }
}
