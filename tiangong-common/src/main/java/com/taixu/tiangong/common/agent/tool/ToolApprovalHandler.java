package com.taixu.tiangong.common.agent.tool;

/**
 * 工具审批处理器：在工具真正执行前决定放行/拒绝。<br>
 * <br>
 * 同步接口只适合「规则审批」（如金额阈值、白名单）或已经有同步审批结果的人机协同；
 * 需要「等人点同意」的场景应实现为 P2 的人机协同节点，而不是在工具线程里无限阻塞。
 * 处理器可以返回 {@link ToolApprovalDecision#DENIED}，框架会把拒绝原因作为工具结果
 * 回放给模型，让模型有机会解释/改方案，而不是直接炸掉整条流。
 */
@FunctionalInterface
public interface ToolApprovalHandler {

    ToolApprovalDecision decide(ToolApprovalRequest request);
}
