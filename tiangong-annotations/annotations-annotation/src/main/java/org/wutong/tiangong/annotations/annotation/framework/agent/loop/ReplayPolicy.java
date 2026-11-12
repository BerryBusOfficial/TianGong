package org.wutong.tiangong.annotations.annotation.framework.agent.loop;

/**
 * 节点可重放性声明（docs/checkpoint-contract.md A5）。<br>
 * <br>
 * 语义边界：本声明<b>只约束崩溃恢复时的行为</b>，不影响节点在一次正常运行中被执行。
 * <br>
 * <ul>
 *     <li>{@link #ALWAYS}（默认）：恢复时允许补跑。适用于纯计算、幂等的节点。</li>
 *     <li>{@link #NEVER}：恢复时拒绝补跑。适用于含外部副作用的节点——
 *         调用外部 API、写文件、发消息、执行 MCP 工具等。
 *         这类节点一旦处于"已开始但未完成"，重复执行会造成重复副作用
 *         （重复扣款、重复发信、重复写盘）。</li>
 * </ul>
 * <br>
 * 默认 {@code ALWAYS} 是为了向后兼容：未声明的既有节点行为不变。
 * 但新写的副作用节点应主动声明 {@code NEVER}。
 */
public enum ReplayPolicy {

    /** 可安全重放：恢复时允许重新执行该节点 */
    ALWAYS,

    /** 不可重放：恢复时拒绝自动重新执行，交由上层决策 */
    NEVER
}
