package com.taixu.tiangong.core.storage.query.condition;

import com.taixu.tiangong.core.storage.StorageRecord;

public sealed interface Condition<R extends StorageRecord<?>>
        permits ComparisonCondition,
        LogicalCondition,
        NotCondition {

    boolean matches(R record);

}
