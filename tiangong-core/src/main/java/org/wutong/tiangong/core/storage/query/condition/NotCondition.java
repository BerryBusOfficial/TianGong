package org.wutong.tiangong.core.storage.query.condition;

import org.wutong.tiangong.core.storage.StorageRecord;

import java.util.Objects;

public record NotCondition<R extends StorageRecord<?>>(
        Condition<R> condition
) implements Condition<R> {

    public NotCondition {
        Objects.requireNonNull(condition, "condition 不能为空");
    }

    @Override
    public boolean matches(R record) {
        return !condition.matches(record);
    }
}
