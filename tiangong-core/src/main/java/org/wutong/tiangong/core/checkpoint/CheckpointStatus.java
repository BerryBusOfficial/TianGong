package org.wutong.tiangong.core.checkpoint;

/**
 * 检查点状态（docs/checkpoint-contract.md A1）。<br>
 * <br>
 * 恢复点只认 {@link #COMPLETED}。{@link #STARTED} 表示"节点已开始但未返回"，
 * 其含义有二：执行中崩溃，或正在阻塞等待外部输入（ENTRANCE 节点）。
 * 两者在恢复时都必须按"未完成"处理，是否允许补跑由节点的
 * {@code ReplayPolicy} 决定。
 */
public enum CheckpointStatus {

    /** 节点已开始执行，尚未返回 */
    STARTED,

    /** 节点已成功返回，可作为恢复点 */
    COMPLETED,

    /** 节点执行抛出异常 */
    FAILED
}
