package com.taixu.tiangong.core.checkpoint;

import java.util.List;
import java.util.Optional;

/**
 * 检查点存储：<b>可替换的扩展点</b>（office/checkpoint-contract.md B1）。<br>
 * <br>
 * 默认实现 {@link StorageCheckpointStore} 建立在框架既有的
 * {@code Storage} 抽象之上；换持久介质（文件 / SQLite / JDBC）时只需替换本接口的实现，
 * 不影响检查点语义。<br>
 * <br>
 * 注意：{@code MapStorage} 是内存实现，<b>不能跨进程存活</b>——
 * 它只适用于测试与"同一进程内的恢复"。跨重启恢复必须先提供持久实现。
 */
public interface CheckpointStore {

    /** 追加一条记录（状态通常为 STARTED），返回持久化后的记录 */
    CheckpointRecord append(CheckpointRecord record);

    /** 原地更新记录状态（COMPLETED / FAILED） */
    void update(CheckpointRecord record);

    /** 按主键查询 */
    Optional<CheckpointRecord> findById(String id);

    /**
     * 按 workflowId 列出全部记录，按 seq 升序。<br>
     * 时间旅行（回看任意历史点）的基础。
     */
    List<CheckpointRecord> history(String workflowId);

    /**
     * 恢复点：该工作流内 seq 最大的 COMPLETED 记录（不变量 A1）。
     */
    Optional<CheckpointRecord> latestCompleted(String workflowId);

    /**
     * 已开始但未完成的记录，按 seq 升序（不变量 A1）。<br>
     * 正常情况下长度 ≤ 1；长度大于 1 说明并发写入了同一 workflowId，属异常信号。
     */
    List<CheckpointRecord> unfinished(String workflowId);

    /**
     * 该工作流已使用的最大 seq；无记录时返回 -1。<br>
     * 供恢复后继续分配 seq，保证幂等与追加式语义。
     */
    long maxSeq(String workflowId);

    /** 清除指定工作流的全部检查点，返回删除条数 */
    int purge(String workflowId);

    /**
     * 列出所有存在检查点的工作流实例 ID（按首次出现顺序无关，去重）。<br>
     * 供跨重启恢复时枚举"可恢复的工作流"。
     */
    List<String> workflowIds();

    /** 是否具备跨进程存活能力。内存实现返回 false，用于在需要跨重启恢复时给出明确提示 */
    default boolean durable() {
        return false;
    }
}
