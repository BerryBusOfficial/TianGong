package com.taixu.tiangong.common.agent.tool;

import com.taixu.tiangong.common.agent.call.AgentCallContext;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次工具审批请求。
 *
 * @param toolName  工具名（模型请求的原始名字）
 * @param arguments 参数（只读副本）
 * @param context   调用者上下文；未注入身份时为 null
 */
public record ToolApprovalRequest(
        String toolName,
        Map<String, Object> arguments,
        AgentCallContext context
) {

    public ToolApprovalRequest {
        // 参数里允许 null（JSON null 是合法值），不能用 Map.copyOf（它拒绝 null 值）
        arguments = arguments == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }
}
