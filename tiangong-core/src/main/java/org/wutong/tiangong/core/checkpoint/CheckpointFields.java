package org.wutong.tiangong.core.checkpoint;

import org.wutong.tiangong.core.storage.query.Field;

/**
 * {@link CheckpointRecord} 的查询字段访问器。<br>
 * 供 {@code StorageQuery} + {@code Conditions} 构造检查点查询，
 * 避免在各处散落字符串字段名。
 */
public final class CheckpointFields {

    private CheckpointFields() {
    }

    public static final Field<CheckpointRecord, String> WORKFLOW_ID =
            Field.of("workflowId", CheckpointRecord::workflowId);

    public static final Field<CheckpointRecord, String> NODE_PATH =
            Field.of("nodePath", CheckpointRecord::nodePath);

    public static final Field<CheckpointRecord, Long> SEQ =
            Field.of("seq", CheckpointRecord::seq);

    public static final Field<CheckpointRecord, CheckpointStatus> STATUS =
            Field.of("status", CheckpointRecord::status);

    public static final Field<CheckpointRecord, String> NODE_NAME =
            Field.of("nodeName", CheckpointRecord::nodeName);

    public static final Field<CheckpointRecord, Long> CREATED_AT =
            Field.of("createdAt", CheckpointRecord::createdAt);
}
