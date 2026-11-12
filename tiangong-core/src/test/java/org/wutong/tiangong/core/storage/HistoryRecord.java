package org.wutong.tiangong.core.storage;

import org.wutong.tiangong.annotations.annotation.framework.field.StorageFields;
import org.wutong.tiangong.annotations.annotation.framework.field.StorageRecords;

/**
 * 验证 @StorageRecords 处理器：应生成 HistoryRecordFields 查询字段类。<br>
 * 注意：注解用全限定名——本包已有 StorageRecords 接口，import 同名注解会被 JLS 忽略。
 */
@StorageRecords
public record HistoryRecord<V extends BaseHistoryItem>(
        @StorageFields("history_key")
        String historyKey,

        @StorageFields
        V value
) implements StorageRecord<String> {

    @Override
    public String getId() {
        return historyKey;
    }
}
