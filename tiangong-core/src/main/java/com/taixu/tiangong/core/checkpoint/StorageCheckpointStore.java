package com.taixu.tiangong.core.checkpoint;

import com.taixu.tiangong.core.storage.Storage;
import com.taixu.tiangong.core.storage.query.StorageQuery;
import com.taixu.tiangong.core.storage.query.condition.Conditions;
import com.taixu.tiangong.core.storage.query.operator.Direction;

import java.util.List;
import java.util.Optional;

/**
 * 默认检查点存储：建立在框架既有的 {@link Storage} 抽象之上
 * （office/checkpoint-contract.md B1）。<br>
 * <br>
 * 职责仅限于把 {@link CheckpointRecord} 存取到某个 {@code Storage} 实现里，
 * 不含任何检查点语义（语义在 {@link CheckpointService}）。<br>
 * <br>
 * <b>跨进程能力取决于传入的 Storage</b>：{@code MapStorage} 是内存实现，
 * 进程退出即丢，只能用于同进程恢复与测试；跨重启恢复必须传入持久实现
 * （文件 / SQLite / JDBC）。{@link #durable()} 会如实上报这一点。
 */
public class StorageCheckpointStore implements CheckpointStore {

    private final Storage<CheckpointRecord, String> storage;
    private final boolean durable;

    public StorageCheckpointStore(Storage<CheckpointRecord, String> storage) {
        this(storage, false);
    }

    public StorageCheckpointStore(Storage<CheckpointRecord, String> storage, boolean durable) {
        if (storage == null) {
            throw new IllegalArgumentException("storage 不能为 null");
        }
        this.storage = storage;
        this.durable = durable;
    }

    @Override
    public CheckpointRecord append(CheckpointRecord record) {
        return storage.insert(record);
    }

    @Override
    public void update(CheckpointRecord record) {
        storage.update(record);
    }

    @Override
    public Optional<CheckpointRecord> findById(String id) {
        return storage.findById(id);
    }

    @Override
    public List<CheckpointRecord> history(String workflowId) {
        return List.copyOf(storage.find(
                StorageQuery.<CheckpointRecord>builder()
                        .where(Conditions.eq(CheckpointFields.WORKFLOW_ID, workflowId))
                        .orderBy(CheckpointFields.SEQ, Direction.ASC)
                        .build()));
    }

    @Override
    public Optional<CheckpointRecord> latestCompleted(String workflowId) {
        return storage.find(
                        StorageQuery.<CheckpointRecord>builder()
                                .where(Conditions.and(
                                        Conditions.eq(CheckpointFields.WORKFLOW_ID, workflowId),
                                        Conditions.eq(CheckpointFields.STATUS, CheckpointStatus.COMPLETED)))
                                .orderBy(CheckpointFields.SEQ, Direction.DESC)
                                .limit(1)
                                .build())
                .stream()
                .findFirst();
    }

    @Override
    public List<CheckpointRecord> unfinished(String workflowId) {
        return List.copyOf(storage.find(
                StorageQuery.<CheckpointRecord>builder()
                        .where(Conditions.and(
                                Conditions.eq(CheckpointFields.WORKFLOW_ID, workflowId),
                                Conditions.eq(CheckpointFields.STATUS, CheckpointStatus.STARTED)))
                        .orderBy(CheckpointFields.SEQ, Direction.ASC)
                        .build()));
    }

    @Override
    public long maxSeq(String workflowId) {
        return storage.find(
                        StorageQuery.<CheckpointRecord>builder()
                                .where(Conditions.eq(CheckpointFields.WORKFLOW_ID, workflowId))
                                .orderBy(CheckpointFields.SEQ, Direction.DESC)
                                .limit(1)
                                .build())
                .stream()
                .map(CheckpointRecord::seq)
                .findFirst()
                .orElse(-1L);
    }

    @Override
    public int purge(String workflowId) {
        return (int) storage.delete(
                StorageQuery.<CheckpointRecord>builder()
                        .where(Conditions.eq(CheckpointFields.WORKFLOW_ID, workflowId))
                        .build());
    }

    @Override
    public List<String> workflowIds() {
        return storage.find(StorageQuery.<CheckpointRecord>builder().build()).stream()
                .map(CheckpointRecord::workflowId)
                .distinct()
                .toList();
    }

    @Override
    public boolean durable() {
        return durable;
    }

    /** 暴露底层存储，供需要跨表/跨记录查询的调用方使用 */
    public Storage<CheckpointRecord, String> storage() {
        return storage;
    }
}
