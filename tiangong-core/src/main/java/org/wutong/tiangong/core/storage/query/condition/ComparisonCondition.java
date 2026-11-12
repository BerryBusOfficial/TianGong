package org.wutong.tiangong.core.storage.query.condition;

import org.wutong.tiangong.core.storage.StorageRecord;
import org.wutong.tiangong.core.storage.query.Field;
import org.wutong.tiangong.core.storage.query.operator.Operator;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

public record ComparisonCondition<R extends StorageRecord<?>, V>(
        Field<R, V> field,
        Operator operator,
        V value
) implements Condition<R> {

    private static BigDecimal toBigDecimal(Number number) {
        return switch (number) {
            case BigDecimal decimal -> decimal;
            case BigInteger integer -> new BigDecimal(integer);
            case Byte value -> BigDecimal.valueOf(value);
            case Short value -> BigDecimal.valueOf(value);
            case Integer value -> BigDecimal.valueOf(value);
            case Long value -> BigDecimal.valueOf(value);
            case Float value -> BigDecimal.valueOf(value.doubleValue());
            case Double value -> BigDecimal.valueOf(value);
            default -> throw new IllegalArgumentException(
                    "不支持的 Number 类型: " + number.getClass()
            );
        };
    }

    @Override
    public boolean matches(R record) {

        V actual = field.get(record);

        return switch (operator) {
            case EQ -> Objects.equals(actual, value);
            case NE -> !Objects.equals(actual, value);
            case GT -> compare(actual, value) > 0;
            case GE -> compare(actual, value) >= 0;
            case LT -> compare(actual, value) < 0;
            case LE -> compare(actual, value) <= 0;
            default -> throw new UnsupportedOperationException(
                    "暂不支持: " + operator
            );
        };
    }

    private int compare(Object actual, Object expected) {

        if (actual == null || expected == null) {
            throw new IllegalArgumentException(
                    "比较操作不允许 null"
            );
        }

        if (actual instanceof Number numberActual
                && expected instanceof Number numberExpected) {

            return toBigDecimal(numberActual)
                    .compareTo(toBigDecimal(numberExpected));
        }

        if (actual instanceof Comparable<?> comparable) {
            @SuppressWarnings("unchecked")
            var comparableActual = (Comparable<Object>) comparable;

            return comparableActual.compareTo(expected);
        }

        throw new IllegalArgumentException(
                "类型 %s 不支持比较"
                        .formatted(actual.getClass().getName())
        );
    }
}