package com.taixu.tiangong.core.function;

import com.taixu.tiangong.common.agent.call.AgentCallContext;

import java.util.Map;

/**
 * 租户级工具策略：不同租户可以有不同的工具白/黑名单，而不是全进程一刀切。<br>
 * <br>
 * 解析顺序：租户专属 scope → 默认 scope。未配置租户走默认；默认也未配置则不限制。
 * 权限仍然要过调用者 scope（{@link ToolAccessPolicy} 两层都查），租户策略不能替代用户权限。
 */
public final class TenantToolPolicy {

    private final Map<String, ToolScope> byTenant;
    private final ToolScope defaultScope;

    private TenantToolPolicy(Map<String, ToolScope> byTenant, ToolScope defaultScope) {
        this.byTenant = Map.copyOf(byTenant);
        this.defaultScope = defaultScope;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 解析租户 scope；返回 null 表示没有租户专属/默认策略，调用方应回退到全局 scope。
     */
    public ToolScope resolve(AgentCallContext context) {
        if (context != null && context.tenantId() != null) {
            ToolScope scoped = byTenant.get(context.tenantId());
            if (scoped != null) {
                return scoped;
            }
        }
        return defaultScope;
    }

    public Map<String, ToolScope> tenantScopes() {
        return byTenant;
    }

    public ToolScope defaultScope() {
        return defaultScope;
    }

    public static final class Builder {

        private final java.util.Map<String, ToolScope> byTenant = new java.util.LinkedHashMap<>();
        private ToolScope defaultScope;

        public Builder tenant(String tenantId, ToolScope scope) {
            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException("tenantId 不能为空");
            }
            byTenant.put(tenantId, scope == null ? ToolScope.unrestricted() : scope);
            return this;
        }

        public Builder defaultScope(ToolScope scope) {
            this.defaultScope = scope;
            return this;
        }

        public TenantToolPolicy build() {
            return new TenantToolPolicy(byTenant, defaultScope);
        }
    }
}
