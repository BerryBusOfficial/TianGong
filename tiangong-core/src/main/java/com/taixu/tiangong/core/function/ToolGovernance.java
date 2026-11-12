package com.taixu.tiangong.core.function;

import com.taixu.tiangong.common.agent.call.AgentCallContext;
import com.taixu.tiangong.common.agent.tool.ToolApprovalDecision;
import com.taixu.tiangong.common.agent.tool.ToolApprovalHandler;
import com.taixu.tiangong.common.agent.tool.ToolApprovalRequest;
import com.taixu.tiangong.common.agent.tool.ToolAuditEvent;
import com.taixu.tiangong.common.agent.tool.ToolAuditSink;
import com.taixu.tiangong.common.agent.tool.ToolAuditStatus;
import com.taixu.tiangong.common.util.JsonUtil;
import com.taixu.tiangong.protocol.event.AgentEventScope;

import java.time.Instant;
import java.util.Map;

/**
 * 工具治理入口：权限判断 → 审批 → 执行 → 审计。<br>
 * <br>
 * <h2>为什么是静态配置</h2>
 * 工具循环由 {@code ToolLoopSupport} 在协议层创建，拿不到 core 的容器装配参数；
 * 但工具治理是「进程级策略」，本来就是全局一份。应用在启动期调用
 * {@link #configure} 一次即可；没有配置时退化为「不限制、不审批、不审计」，
 * 与改造前行为一致（向后兼容），而不是悄悄启用一套默认权限。<br>
 * <br>
 * <h2>失败语义</h2>
 * 权限不足/审批拒绝抛 {@link ToolAccessDeniedException}，由工具循环转成工具结果回放给
 * 模型；审计先写成功再抛，保证「拒绝也留痕」。审批处理器自身异常按<b>拒绝</b>处理，
 * 不按放行 —— 审批链路故障不能变成提权通道。
 */
public final class ToolGovernance {

    private static volatile ToolScope toolScope = ToolScope.unrestricted();
    private static volatile TenantToolPolicy tenantToolPolicy;
    private static volatile ToolApprovalHandler approvalHandler;
    private static volatile ToolAuditSink auditSink = ToolAuditSink.NOOP;
    private static volatile ToolRuntimePolicy runtimePolicy = ToolRuntimePolicy.defaults();
    private static volatile TenantRateLimiter tenantRateLimiter;

    private ToolGovernance() {
    }

    /** 一次性配置部署策略、审批与审计 */
    public static void configure(ToolScope scope, ToolApprovalHandler approval, ToolAuditSink audit) {
        toolScope = scope == null ? ToolScope.unrestricted() : scope;
        approvalHandler = approval;
        auditSink = audit == null ? ToolAuditSink.NOOP : audit;
    }

    /** 恢复默认：不限制、不审批、不审计（测试/嵌入式宿主用） */
    public static void reset() {
        configure(ToolScope.unrestricted(), null, ToolAuditSink.NOOP);
        tenantToolPolicy = null;
        runtimePolicy = ToolRuntimePolicy.defaults();
        tenantRateLimiter = null;
    }

    public static ToolScope toolScope() {
        return toolScope;
    }

    /**
     * 按调用者解析工具策略：配置了租户策略时优先用租户 scope，否则用全局 scope。
     */
    public static ToolScope toolScope(AgentCallContext context) {
        TenantToolPolicy policy = tenantToolPolicy;
        if (policy == null) {
            return toolScope;
        }
        ToolScope resolved = policy.resolve(context);
        return resolved == null ? toolScope : resolved;
    }

    public static void setTenantToolPolicy(TenantToolPolicy policy) {
        tenantToolPolicy = policy;
    }

    public static void setToolScope(ToolScope scope) {
        toolScope = scope == null ? ToolScope.unrestricted() : scope;
    }

    public static void setApprovalHandler(ToolApprovalHandler handler) {
        approvalHandler = handler;
    }

    public static void setAuditSink(ToolAuditSink sink) {
        auditSink = sink == null ? ToolAuditSink.NOOP : sink;
    }

    public static void setRuntimePolicy(ToolRuntimePolicy policy) {
        runtimePolicy = policy == null ? ToolRuntimePolicy.defaults() : policy;
    }

    public static void setTenantRateLimiter(TenantRateLimiter limiter) {
        tenantRateLimiter = limiter;
    }

    /**
     * 执行工具调用。
     *
     * @param definition 注册表里的工具定义
     * @param arguments  参数
     * @param toolName   模型请求的原始工具名（审计/审批展示用；null 时用全名）
     */
    public static Object invoke(AgentFunctionDefinition definition,
                                Map<String, Object> arguments,
                                String toolName) {
        if (definition == null) {
            throw new ToolAccessDeniedException("工具定义不存在");
        }
        String displayName = toolName == null || toolName.isBlank()
                ? definition.fullName() : toolName;
        AgentCallContext context = AgentCallContext.current();
        long start = System.nanoTime();

        ToolAccessPolicy.Decision access = ToolAccessPolicy.check(definition, context);
        if (!access.allowed()) {
            audit(definition, displayName, context, arguments, ToolAuditStatus.DENIED,
                    start, access.reason());
            throw new ToolAccessDeniedException(access.reason());
        }

        TenantRateLimiter limiter = tenantRateLimiter;
        if (limiter != null && context != null && context.tenantId() != null
                && !limiter.tryAcquire(context.tenantId())) {
            String reason = "租户 [" + context.tenantId() + "] 工具调用超出速率限制";
            audit(definition, displayName, context, arguments, ToolAuditStatus.DENIED, start, reason);
            throw new ToolAccessDeniedException(reason);
        }

        if (definition.isRequiresApproval()) {
            ToolApprovalHandler handler = approvalHandler;
            if (handler == null) {
                String reason = "工具 [" + displayName + "] 需要审批，但未配置 ToolApprovalHandler";
                audit(definition, displayName, context, arguments, ToolAuditStatus.APPROVAL_DENIED,
                        start, reason);
                throw new ToolAccessDeniedException(reason);
            }
            ToolApprovalDecision decision;
            try {
                decision = handler.decide(new ToolApprovalRequest(displayName, arguments, context));
            } catch (RuntimeException e) {
                String reason = "工具 [" + displayName + "] 审批处理器异常，按拒绝处理: " + e.getMessage();
                audit(definition, displayName, context, arguments, ToolAuditStatus.APPROVAL_DENIED,
                        start, reason);
                throw new ToolAccessDeniedException(reason);
            }
            if (decision != ToolApprovalDecision.APPROVED) {
                String reason = "工具 [" + displayName + "] 未通过审批";
                audit(definition, displayName, context, arguments, ToolAuditStatus.APPROVAL_DENIED,
                        start, reason);
                throw new ToolAccessDeniedException(reason);
            }
        }

        try {
            AgentEventScope.Binding eventBinding = AgentEventScope.capture();
            Object result = runtimePolicy.execute(displayName, () -> {
                java.util.function.Supplier<Object> base = () -> definition.invoke(arguments);
                java.util.function.Supplier<Object> withEvents = eventBinding == null
                        ? base : () -> AgentEventScope.callWith(eventBinding, base);
                return AgentCallContext.callWith(context, withEvents);
            });
            audit(definition, displayName, context, arguments, ToolAuditStatus.SUCCESS, start, null);
            return result;
        } catch (RuntimeException e) {
            audit(definition, displayName, context, arguments, ToolAuditStatus.FAILED, start,
                    e.getMessage());
            throw e;
        }
    }

    private static void audit(AgentFunctionDefinition definition, String toolName,
                              AgentCallContext context, Map<String, Object> arguments,
                              ToolAuditStatus status, long startNanos, String detail) {
        ToolAuditSink sink = auditSink;
        if (sink == null || sink == ToolAuditSink.NOOP) {
            return;
        }
        try {
            sink.record(new ToolAuditEvent(
                    toolName,
                    context == null ? null : context.userId(),
                    context == null ? null : context.tenantId(),
                    context == null ? null : context.traceId(),
                    argumentsJson(arguments),
                    status,
                    (System.nanoTime() - startNanos) / 1_000_000,
                    detail,
                    Instant.now()));
        } catch (RuntimeException ignored) {
            // 审计是旁路：写失败不能影响工具结果
        }
    }

    private static String argumentsJson(Map<String, Object> arguments) {
        if (arguments == null) {
            return "null";
        }
        try {
            return JsonUtil.toJson(arguments);
        } catch (Exception e) {
            return String.valueOf(arguments);
        }
    }
}
