package com.taixu.tiangong.common.agent.tool;

/**
 * 工具审批结果。<br>
 * <b>默认拒绝</b>：没有配置审批处理器时，声明了 {@code requiresApproval} 的工具
 * 必须拒绝执行 —— 否则「要求审批」只是文档里的装饰，生产上会静默放行。
 */
public enum ToolApprovalDecision {

    APPROVED,
    DENIED
}
