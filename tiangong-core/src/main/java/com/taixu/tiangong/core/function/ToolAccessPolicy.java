package com.taixu.tiangong.core.function;

import com.taixu.tiangong.common.agent.call.AgentCallContext;
import com.taixu.tiangong.common.agent.tool.ToolAuditStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具访问策略的纯判断层：部署级 {@link ToolScope} + 调用者 scope。<br>
 * <br>
 * 与 {@link ToolGovernance#invoke} 拆开，是为了让「工具该不该出现在清单里」和
 * 「工具能不能执行」共用同一条判据 —— 否则模型看得到、执行时却被拒，属于两套规则漂移。
 */
public final class ToolAccessPolicy {

    private ToolAccessPolicy() {
    }

    public record Decision(boolean allowed, String reason, ToolAuditStatus status) {
        public static Decision allow() {
            return new Decision(true, null, null);
        }

        public static Decision deny(String reason) {
            return new Decision(false, reason, ToolAuditStatus.DENIED);
        }
    }

    /** 按当前线程的调用者上下文判断 */
    public static Decision check(AgentFunctionDefinition definition) {
        return check(definition, AgentCallContext.current());
    }

    public static Decision check(AgentFunctionDefinition definition, AgentCallContext context) {
        if (definition == null) {
            return Decision.deny("工具定义不存在");
        }
        ToolScope scope = ToolGovernance.toolScope();
        String shortName = definition.getName();
        String fullName = definition.fullName();
        if (scope.denies(shortName) || scope.denies(fullName)) {
            return Decision.deny("工具被部署策略禁用: " + fullName);
        }
        if (!scope.allowPatterns().isEmpty()
                && !scope.permits(shortName) && !scope.permits(fullName)) {
            return Decision.deny("工具不在部署白名单内: " + fullName);
        }
        if (definition.getScopes().isEmpty()) {
            return Decision.allow();
        }
        if (context == null) {
            return Decision.deny("工具 [" + fullName + "] 需要调用者权限，但当前请求没有 AgentCallContext");
        }
        List<String> missing = new ArrayList<>();
        for (String required : definition.getScopes()) {
            if (!context.hasScope(required)) {
                missing.add(required);
            }
        }
        if (!missing.isEmpty()) {
            return Decision.deny("缺少工具 [" + fullName + "] 所需权限: " + missing);
        }
        return Decision.allow();
    }

    /** 过滤下发给模型的工具清单时使用；与执行期同一条判据 */
    public static boolean isVisible(AgentFunctionDefinition definition, AgentCallContext context) {
        return check(definition, context).allowed();
    }
}
