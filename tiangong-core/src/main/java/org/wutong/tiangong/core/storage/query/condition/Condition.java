package org.wutong.tiangong.core.storage.query.condition;

import org.wutong.tiangong.core.storage.StorageRecord;

public sealed interface Condition<R extends StorageRecord<?>>
        permits ComparisonCondition,
        LogicalCondition,
        NotCondition {

    boolean matches(R record);

}
