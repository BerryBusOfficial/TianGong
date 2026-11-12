package com.taixu.tiangong.common.agent.tool;

/**
 * 工具审计状态。用枚举而不是自由字符串，保证审计端可以稳定聚合。
 */
public enum ToolAuditStatus {

    /** 执行成功 */
    SUCCESS,
    /** 执行失败（参数/业务/网络异常） */
    FAILED,
    /** 没有通过 scope 校验，未执行 */
    DENIED,
    /** 需要审批但被拒绝/无审批处理器，未执行 */
    APPROVAL_DENIED
}
