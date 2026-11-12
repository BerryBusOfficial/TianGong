package org.wutong.tiangong.core.storage.query;

import org.wutong.tiangong.core.storage.StorageRecord;
import org.wutong.tiangong.core.storage.query.operator.Direction;

import java.util.Objects;

public record Order<R extends StorageRecord<?>>(
        Field<R, ?> field,
        Direction direction
) {

    public Order {
        Objects.requireNonNull(field, "field 不能为空");
        Objects.requireNonNull(direction, "direction 不能为空");
    }

    public static <R extends StorageRecord<?>> Order<R> asc(
            Field<R, ?> field
    ) {
        return new Order<>(field, Direction.ASC);
    }

    public static <R extends StorageRecord<?>> Order<R> desc(
            Field<R, ?> field
    ) {
        return new Order<>(field, Direction.DESC);
    }
}