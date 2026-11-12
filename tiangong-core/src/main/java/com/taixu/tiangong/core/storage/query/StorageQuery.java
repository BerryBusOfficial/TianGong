package com.taixu.tiangong.core.storage.query;

import com.taixu.tiangong.core.storage.StorageRecord;
import com.taixu.tiangong.core.storage.query.condition.Condition;
import com.taixu.tiangong.core.storage.query.operator.Direction;

import java.util.List;
import java.util.Objects;

/**
 * 存储查询。
 *
 * <p>用于描述对 StorageRecords 的查询条件、排序以及分页信息。
 * Storage 的具体实现负责解释并执行该查询。</p>
 *
 * @param <R> 记录类型
 */
public record StorageQuery<R extends StorageRecord<?>>(
        Condition<R> condition,
        List<Order<R>> orders,
        int offset,
        int limit
) {

    public StorageQuery {
        orders = orders == null
                ? List.of()
                : List.copyOf(orders);

        if (offset < 0) {
            throw new IllegalArgumentException(
                    "offset 不能小于 0"
            );
        }

        if (limit == 0 || limit < -1) {
            throw new IllegalArgumentException(
                    "limit 必须为 -1 或大于 0"
            );
        }
    }

    /**
     * 创建查询构造器。
     */
    public static <R extends StorageRecord<?>> Builder<R> builder() {
        return new Builder<>();
    }

    /**
     * 查询是否包含过滤条件。
     */
    public boolean hasCondition() {
        return condition != null;
    }

    /**
     * 是否需要排序。
     */
    public boolean hasOrder() {
        return !orders.isEmpty();
    }

    /**
     * 是否启用了分页限制。
     */
    public boolean hasLimit() {
        return limit >= 0;
    }

    /**
     * 查询构造器。
     */
    public static final class Builder<R extends StorageRecord<?>> {

        private final List<Order<R>> orders = new java.util.ArrayList<>();
        private Condition<R> condition;
        private int offset = 0;
        private int limit = -1;

        public static <R extends StorageRecord<?>> StorageQuery<R> all() {
            return new StorageQuery<>(
                    null,
                    List.of(),
                    0,
                    -1
            );
        }

        public Builder<R> where(Condition<R> condition) {
            this.condition = Objects.requireNonNull(
                    condition,
                    "condition 不能为空"
            );
            return this;
        }

        public Builder<R> orderBy(Order<R> order) {
            this.orders.add(
                    Objects.requireNonNull(
                            order,
                            "order 不能为空"
                    )
            );
            return this;
        }

        /**
         * 便捷重载：按字段 + 方向排序
         */
        public Builder<R> orderBy(Field<R, ?> field, Direction direction) {
            return orderBy(new Order<>(field, direction));
        }

        public Builder<R> orderBy(List<Order<R>> orders) {
            Objects.requireNonNull(
                    orders,
                    "orders 不能为空"
            );

            orders.forEach(this::orderBy);
            return this;
        }

        public Builder<R> offset(int offset) {
            if (offset < 0) {
                throw new IllegalArgumentException(
                        "offset 不能小于 0"
                );
            }

            this.offset = offset;
            return this;
        }

        public Builder<R> limit(int limit) {
            if (limit == 0 || limit < -1) {
                throw new IllegalArgumentException(
                        "limit 必须为 -1 或大于 0"
                );
            }

            this.limit = limit;
            return this;
        }

        public StorageQuery<R> build() {
            return new StorageQuery<>(
                    condition,
                    orders,
                    offset,
                    limit
            );
        }
    }
}