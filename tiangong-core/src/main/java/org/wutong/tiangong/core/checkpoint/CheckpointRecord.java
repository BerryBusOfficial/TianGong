package org.wutong.tiangong.core.checkpoint;

import org.wutong.tiangong.core.storage.StorageRecord;

/**
 * 检查点记录：单个节点边界上的一次状态存档（docs/checkpoint-contract.md）。<br>
 * <br>
 * <b>主键（不变量 A7）</b>：{@code (workflowId, nodePath, seq)}，序列化为
 * {@code workflowId|nodePath|seq}。其中 {@code nodePath} 为层级路径——
 * 当前恒等于节点名，预留 {@code /} 分隔的层级以便将来支持"节点内嵌子工作流"，
 * 届时子节点路径形如 {@code parent/sub}，无需迁移历史数据。<br>
 * <br>
 * <b>生命周期</b>：append 时状态为 {@link CheckpointStatus#STARTED}；
 * 节点返回后 update 为 {@link CheckpointStatus#COMPLETED} 并写入 {@code selectedNext}；
 * 抛异常则 update 为 {@link CheckpointStatus#FAILED}。<br>
 * <br>
 * 记录为追加式：同一节点在恢复后再次执行会产生更大的 {@code seq} 的新记录，
 * 旧记录保留 —— 时间旅行（回看任意历史点）因此天然可用。
 */
public class CheckpointRecord implements StorageRecord<String> {

    /** 主键分隔符 */
    public static final String KEY_SEPARATOR = "|";

    private final String workflowId;
    private final String nodePath;
    private final long seq;
    private final String nodeName;
    private final String nodeType;
    /** 迁移来源节点名（不变量 A2）；工作流首个节点为 null */
    private final String fromNode;
    /** 是否可重放（不变量 A5），取自节点的 {@code @AgentNode(replay)} 声明 */
    private final boolean replayable;
    private final long createdAt;

    /**
     * 快照结构版本（不变量 A4）。<br>
     * STARTED 阶段为"节点执行前"状态的版本；COMPLETED 时被执行后状态覆盖。
     */
    private int schemaVersion;
    /**
     * 上下文实现类全限定名（不变量 A4）。<br>
     * <b>负载语义</b>：STARTED 阶段是<b>节点执行前</b>的状态（用于重跑该节点）；
     * COMPLETED 时替换为<b>节点执行后</b>的状态（用于从 {@code selectedNext} 继续）。
     */
    private String contextType;
    private String contextPayload;

    private CheckpointStatus status;
    /** 本轮选中的出边（不变量 A2）；END 节点为 null */
    private String selectedNext;
    private String errorMessage;

    public CheckpointRecord(String workflowId, String nodePath, long seq,
                            String nodeName, String nodeType, String fromNode,
                            boolean replayable, int schemaVersion,
                            String contextType, String contextPayload) {
        this.workflowId = workflowId;
        this.nodePath = nodePath;
        this.seq = seq;
        this.nodeName = nodeName;
        this.nodeType = nodeType;
        this.fromNode = fromNode;
        this.replayable = replayable;
        this.schemaVersion = schemaVersion;
        this.contextType = contextType;
        this.contextPayload = contextPayload;
        this.createdAt = System.currentTimeMillis();
        this.status = CheckpointStatus.STARTED;
    }

    /** 主键：{@code workflowId|nodePath|seq} */
    @Override
    public String getId() {
        return workflowId + KEY_SEPARATOR + nodePath + KEY_SEPARATOR + seq;
    }

    public String workflowId() {
        return workflowId;
    }

    public String nodePath() {
        return nodePath;
    }

    public long seq() {
        return seq;
    }

    public String nodeName() {
        return nodeName;
    }

    public String nodeType() {
        return nodeType;
    }

    public String fromNode() {
        return fromNode;
    }

    public boolean replayable() {
        return replayable;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public String contextType() {
        return contextType;
    }

    public String contextPayload() {
        return contextPayload;
    }

    public long createdAt() {
        return createdAt;
    }

    public CheckpointStatus status() {
        return status;
    }

    public String selectedNext() {
        return selectedNext;
    }

    public String errorMessage() {
        return errorMessage;
    }

    /**
     * 标记为已完成，并记录本轮选中的出边（不变量 A2）。<br>
     * <br>
     * 同时把负载替换为<b>节点执行后</b>的状态快照 —— 恢复点需要的是"从
     * {@code selectedNext} 继续"所需的输入状态，即节点执行后的状态；
     * 而 STARTED 阶段的负载是执行前状态，用于重跑本节点。两者用途不同，不可互相替代。
     *
     * @param postState    节点执行后的状态快照
     * @param selectedNext 本轮选中的出边；END 节点传 null
     */
    public void markCompleted(org.wutong.tiangong.common.agent.checkpoint.ContextSnapshot postState,
                              String selectedNext) {
        if (postState != null) {
            this.schemaVersion = postState.schemaVersion();
            this.contextType = postState.contextType();
            this.contextPayload = postState.payload();
        }
        this.status = CheckpointStatus.COMPLETED;
        this.selectedNext = selectedNext;
        this.errorMessage = null;
    }

    /** 标记为失败 */
    public void markFailed(String errorMessage) {
        this.status = CheckpointStatus.FAILED;
        this.errorMessage = errorMessage;
    }

    /** 是否处于"已开始但未完成"——恢复时需要特殊处理的状态（不变量 A1） */
    public boolean startedOnly() {
        return status == CheckpointStatus.STARTED;
    }

    @Override
    public String toString() {
        return "Checkpoint[" + getId() + " " + nodeType + " " + status
                + (selectedNext != null ? " -> " + selectedNext : "") + "]";
    }
}
