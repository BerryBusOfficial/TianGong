package org.wutong.tiangong.core.storage.query.condition;

import org.wutong.tiangong.core.storage.StorageRecord;
import org.wutong.tiangong.core.storage.query.operator.LogicalOperator;

import java.util.List;

public record LogicalCondition<R extends StorageRecord<?>>(
        LogicalOperator operator,
        List<Condition<R>> conditions
) implements Condition<R> {

    public LogicalCondition {
        if (operator == null) {
            throw new IllegalArgumentException("逻辑操作符不能为空");
        }

        if (conditions == null || conditions.isEmpty()) {
            throw new IllegalArgumentException("逻辑条件不能为空");
        }

        conditions = List.copyOf(conditions);
    }

    @Override
    public boolean matches(R record) {
        return switch (operator) {
            case AND -> conditions.stream()
                    .allMatch(condition -> condition.matches(record));

            case OR -> conditions.stream()
                    .anyMatch(condition -> condition.matches(record));
        };
    }
}
