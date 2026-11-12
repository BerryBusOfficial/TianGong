package com.taixu.tiangong.core.storage.query.condition;

import com.taixu.tiangong.core.storage.StorageRecord;
import com.taixu.tiangong.core.storage.query.Field;
import com.taixu.tiangong.core.storage.query.operator.LogicalOperator;
import com.taixu.tiangong.core.storage.query.operator.Operator;

import java.util.List;

public final class Conditions {

    private Conditions() {
    }

    public static <R extends StorageRecord<?>, V> Condition<R> eq(
            Field<R, V> field,
            V value
    ) {
        return new ComparisonCondition<>(
                field,
                Operator.EQ,
                value
        );
    }

    public static <R extends StorageRecord<?>, V> Condition<R> ne(
            Field<R, V> field,
            V value
    ) {
        return new ComparisonCondition<>(
                field,
                Operator.NE,
                value
        );
    }

    public static <R extends StorageRecord<?>, V extends Comparable<? super V>> Condition<R> lt(
            Field<R, V> field,
            V value
    ) {
        return new ComparisonCondition<>(
                field,
                Operator.LT,
                value
        );
    }

    public static <R extends StorageRecord<?>, V extends Comparable<? super V>> Condition<R> le(
            Field<R, V> field,
            V value
    ) {
        return new ComparisonCondition<>(
                field,
                Operator.LE,
                value
        );
    }

    public static <R extends StorageRecord<?>, V extends Comparable<? super V>> Condition<R> gt(
            Field<R, V> field,
            V value
    ) {
        return new ComparisonCondition<>(
                field,
                Operator.GT,
                value
        );
    }

    public static <R extends StorageRecord<?>, V extends Comparable<? super V>> Condition<R> ge(
            Field<R, V> field,
            V value
    ) {
        return new ComparisonCondition<>(
                field,
                Operator.GE,
                value
        );
    }

    @SafeVarargs
    public static <R extends StorageRecord<?>> Condition<R> and(
            Condition<R>... conditions
    ) {
        return new LogicalCondition<>(
                LogicalOperator.AND,
                List.of(conditions)
        );
    }

    @SafeVarargs
    public static <R extends StorageRecord<?>> Condition<R> or(
            Condition<R>... conditions
    ) {
        return new LogicalCondition<>(
                LogicalOperator.OR,
                List.of(conditions)
        );
    }

    public static <R extends StorageRecord<?>> Condition<R> not(
            Condition<R> condition
    ) {
        return new NotCondition<>(condition);
    }

}