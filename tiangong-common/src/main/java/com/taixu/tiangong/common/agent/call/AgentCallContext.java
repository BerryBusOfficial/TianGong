package com.taixu.tiangong.common.agent.call;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 调用者上下文：一次 Agent 调用「以谁的身份、在哪个租户、拿到哪些权限」的载体。<br>
 * <br>
 * <h2>与 {@link com.taixu.tiangong.common.agent.context.AgentContext} 的区别</h2>
 * {@code AgentContext} 是<b>工作流状态</b>（历史、临时变量、取消标志），会被检查点
 * 序列化；本类是<b>调用者身份</b>，属于请求边界，<b>不应</b>写进检查点/上下文快照 ——
 * 否则一次恢复会把上一个用户的权限带进新请求，这是权限越界而不是功能缺陷。<br>
 * <br>
 * <h2>线程模型</h2>
 * 用 {@link ThreadLocal} 传递。HTTP 入口在 fork 执行线程时显式 bind（框架负责），
 * 流式读取在虚拟线程里跑时不会被自动继承，因此需要 {@link #capture()} +
 * {@link #runWith(Binding, Runnable)} 显式搬运。<br>
 * <br>
 * <b>安全边界</b>：{@code scopes} 来自网关/认证层。框架不做身份认证；如果直接把这组
 * header 暴露给公网而不经过网关注入/覆盖，等于让客户端自报权限。生产部署必须由
 * 网关校验身份后再写入 {@code X-User-Id / X-Tenant-Id / X-Scopes}。
 */
public record AgentCallContext(
        String userId,
        String tenantId,
        Set<String> scopes,
        String traceId,
        Map<String, String> attributes
) {

    private static final ThreadLocal<AgentCallContext> CURRENT = new ThreadLocal<>();

    public AgentCallContext {
        scopes = scopes == null
                ? Set.of()
                : Set.copyOf(scopes.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList());
        attributes = attributes == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    public AgentCallContext(String userId, String tenantId, Set<String> scopes, String traceId) {
        this(userId, tenantId, scopes, traceId, Map.of());
    }

    // ==================== 权限判断 ====================

    /**
     * 是否持有某个 scope。<br>
     * 支持两种通配：
     * <ul>
     *     <li>调用方持有 {@code *}：拥有全部；</li>
     *     <li>调用方持有 {@code report:*}：匹配 {@code report:draft} 等前缀；</li>
     *     <li>声明侧写 {@code report:*} 而调用方只有 {@code report:draft}：也视为满足
     *         （用于「该工具属于 report 域」这种粗粒度声明）。</li>
     * </ul>
     */
    public boolean hasScope(String required) {
        if (required == null || required.isBlank()) {
            return true;
        }
        for (String granted : scopes) {
            if (scopeMatches(granted, required)) {
                return true;
            }
        }
        return false;
    }

    /** 是否满足全部声明 scope（空集合 = 不需要权限） */
    public boolean hasAllScopes(Collection<String> required) {
        if (required == null || required.isEmpty()) {
            return true;
        }
        for (String scope : required) {
            if (!hasScope(scope)) {
                return false;
            }
        }
        return true;
    }

    private static boolean scopeMatches(String granted, String required) {
        if ("*".equals(granted) || granted.equals(required)) {
            return true;
        }
        if (granted.endsWith(":*")) {
            String prefix = granted.substring(0, granted.length() - 1);   // 保留冒号
            if (required.startsWith(prefix)) {
                return true;
            }
        }
        if (required.endsWith(":*")) {
            String prefix = required.substring(0, required.length() - 1);
            return granted.startsWith(prefix);
        }
        return false;
    }

    // ==================== 线程绑定 ====================

    /** 当前线程上下文；未绑定时返回 null（单机/测试场景允许无身份） */
    public static AgentCallContext current() {
        return CURRENT.get();
    }

    /** 绑定并返回原值，供 try/finally 恢复 */
    public static AgentCallContext bind(AgentCallContext context) {
        AgentCallContext previous = CURRENT.get();
        if (context == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(context);
        }
        return previous;
    }

    public static void restore(AgentCallContext previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    public static void runWith(AgentCallContext context, Runnable action) {
        AgentCallContext previous = bind(context);
        try {
            action.run();
        } finally {
            restore(previous);
        }
    }

    public static <T> T callWith(AgentCallContext context, Supplier<T> action) {
        AgentCallContext previous = bind(context);
        try {
            return action.get();
        } finally {
            restore(previous);
        }
    }

    /** 跨线程快照：ThreadLocal 的值本身不可变，直接返回即可 */
    public static Binding capture() {
        return new Binding(CURRENT.get());
    }

    public record Binding(AgentCallContext context) {
        public void run(Runnable action) {
            runWith(context, action);
        }
    }

    // ==================== HTTP 头解析 ====================

    /**
     * 从入口请求头构造上下文。<br>
     * 没有任何身份/追踪头时返回 null，保持既有单机行为不变（不强行伪造一个匿名租户）。
     * header 名大小写不敏感；{@code traceparent} 取 W3C trace-id 段。
     */
    public static AgentCallContext fromHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        String userId = header(headers, "X-User-Id");
        String tenantId = header(headers, "X-Tenant-Id");
        String scopesHeader = header(headers, "X-Scopes");
        String traceId = header(headers, "X-Trace-Id");
        if (traceId == null) {
            traceId = traceIdFromTraceparent(header(headers, "traceparent"));
        }
        if (userId == null && tenantId == null && scopesHeader == null && traceId == null) {
            return null;
        }
        Set<String> scopes = new LinkedHashSet<>();
        if (scopesHeader != null) {
            for (String scope : scopesHeader.split("[,\\s]+")) {
                if (!scope.isBlank()) {
                    scopes.add(scope.trim());
                }
            }
        }
        return new AgentCallContext(userId, tenantId, scopes, traceId);
    }

    private static String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                String value = entry.getValue();
                return value == null || value.isBlank() ? null : value.trim();
            }
        }
        return null;
    }

    /** traceparent: 00-<32 hex trace-id>-<16 hex span-id>-<flags> */
    private static String traceIdFromTraceparent(String traceparent) {
        if (traceparent == null) {
            return null;
        }
        String[] parts = traceparent.split("-");
        return parts.length >= 3 ? parts[1] : null;
    }

    @Override
    public String toString() {
        return "AgentCallContext[user=" + userId + ", tenant=" + tenantId
                + ", scopes=" + scopes + ", trace=" + traceId + "]";
    }
}
