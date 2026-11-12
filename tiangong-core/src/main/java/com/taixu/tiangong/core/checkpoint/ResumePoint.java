package com.taixu.tiangong.core.checkpoint;

import com.taixu.tiangong.common.agent.context.AgentContext;

/**
 * 恢复点：描述"从哪个节点、用什么状态、以何种方式"继续一个工作流
 * （office/checkpoint-contract.md A1/A2）。<br>
 * <br>
 * 两种恢复方式：<br>
 * <ul>
 *     <li>{@code replay = true}：最后一条记录是 STARTED 未完成 → <b>重跑</b>该节点。
 *         状态取自该记录的负载（节点执行前状态）。仅当节点声明
 *         {@code ReplayPolicy.ALWAYS} 时允许。</li>
 *     <li>{@code replay = false}：最后一条 COMPLETED 记录的 {@code selectedNext} →
 *         <b>从记录的出边继续</b>。状态取自该记录负载（节点执行后状态）。
 *         这是 A2 的落地形式：不重新求值 BRANCH / condition，直接用记录下来的决策。</li>
 * </ul>
 *
 * @param workflowId 工作流实例 ID
 * @param record     作为恢复依据的检查点记录
 * @param nextNode   要执行的节点名
 * @param replay     是否属于"重跑未完成节点"
 * @param state      已还原的上下文
 */
public record ResumePoint(
        String workflowId,
        CheckpointRecord record,
        String nextNode,
        boolean replay,
        AgentContext state
) {

    @Override
    public String toString() {
        return "ResumePoint[" + workflowId + " " + (replay ? "重跑" : "继续")
                + " " + nextNode + " from seq=" + record.seq() + "]";
    }
}
