package org.wutong.tiangong.core.storage;

import org.wutong.tiangong.annotations.annotation.framework.Component;
import org.wutong.tiangong.common.exception.SystemRuntimeException;
import org.wutong.tiangong.core.storage.query.Order;
import org.wutong.tiangong.core.storage.query.StorageQuery;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Component("mapStorage")
public class MapStorage<R extends StorageRecord<ID>, ID> implements Storage<R, ID> {

    private final Map<ID, R> storage;

    public MapStorage() {
        this.storage = new ConcurrentHashMap<>();
    }

    public MapStorage(Map<ID, R> storage) {
        this.storage = storage;
    }

    // =========================
    // INSERT
    // =========================

    @Override
    public R insert(R record) {
        var previous = storage.putIfAbsent(
                record.getId(),
                record
        );

        if (previous != null) {
            throw new SystemRuntimeException(
                    "记录 %s 已存在".formatted(record.getId()),
                    new IllegalArgumentException()
            );
        }

        return record;
    }

    @Override
    public Collection<R> insertAll(Collection<R> records) {
        var inserted = new ArrayList<R>();

        for (var record : records) {
            inserted.add(insert(record));
        }

        return inserted;
    }

    // =========================
    // SELECT
    // =========================

    @Override
    public Optional<R> findById(ID id) {
        return Optional.ofNullable(
                storage.get(id)
        );
    }

    @Override
    public Stream<R> stream(StorageQuery<R> query) {
        var stream = filter(query);

        if (!query.orders().isEmpty()) {
            stream = stream.sorted(buildComparator(query.orders()));
        }

        if (query.offset() > 0) {
            stream = stream.skip(query.offset());
        }

        if (query.limit() >= 0) {
            stream = stream.limit(query.limit());
        }

        return stream;
    }

    private Stream<R> filter(StorageQuery<R> query) {
        Stream<R> stream = storage.values().stream();

        if (query.condition() != null) {
            stream = stream.filter(query.condition()::matches);
        }

        return stream;
    }

    @Override
    public Collection<R> find(StorageQuery<R> query) {
        return stream(query).toList();
    }

    @Override
    public long count(StorageQuery<R> query) {
        return filter(query).count();
    }

    @Override
    public boolean exists(StorageQuery<R> query) {
        return filter(query).findAny().isPresent();
    }

    // =========================
    // UPDATE
    // =========================

    @Override
    public R update(R record) {
        var id = record.getId();

        if (!storage.containsKey(id)) {
            throw new SystemRuntimeException(
                    "记录 %s 不存在".formatted(id),
                    new IllegalArgumentException()
            );
        }

        storage.put(id, record);

        return record;
    }

    // =========================
    // DELETE
    // =========================

    @Override
    public boolean deleteById(ID id) {
        return storage.remove(id) != null;
    }

    @Override
    public long delete(StorageQuery<R> query) {
        var records = find(query);

        long count = 0;

        for (var record : records) {
            if (storage.remove(record.getId(), record)) {
                count++;
            }
        }

        return count;
    }

    // =========================
    // LIFECYCLE
    // =========================

    @Override
    public void clear() {
        storage.clear();
    }

    // =========================
    // INTERNAL
    // =========================

    private Comparator<R> buildComparator(
            List<Order<R>> orders
    ) {
        Comparator<R> comparator = null;

        for (var order : orders) {

            Comparator<R> current =
                    Comparator.comparing(
                            order.field()::get,
                            this::compareValues
                    );

            if (order.direction().isDescending()) {
                current = current.reversed();
            }

            comparator = comparator == null
                    ? current
                    : comparator.thenComparing(current);
        }

        return comparator;
    }

    @SuppressWarnings({
            "unchecked",
            "rawtypes"
    })
    private int compareValues(
            Object left,
            Object right
    ) {

        if (left == right) {
            return 0;
        }

        if (left == null) {
            return -1;
        }

        if (right == null) {
            return 1;
        }

        if (left instanceof Number leftNumber
                && right instanceof Number rightNumber) {

            return toBigDecimal(leftNumber)
                    .compareTo(toBigDecimal(rightNumber));
        }

        if (left instanceof Comparable comparable) {
            return comparable.compareTo(right);
        }

        throw new IllegalArgumentException(
                "字段类型不支持排序: "
                        + left.getClass().getName()
        );
    }

    private BigDecimal toBigDecimal(
            Number number
    ) {
        return switch (number) {
            case BigDecimal value -> value;
            case java.math.BigInteger value -> new BigDecimal(value);
            case Byte value -> BigDecimal.valueOf(value);
            case Short value -> BigDecimal.valueOf(value);
            case Integer value -> BigDecimal.valueOf(value);
            case Long value -> BigDecimal.valueOf(value);
            case Float value -> BigDecimal.valueOf(
                    value.doubleValue()
            );
            case Double value -> BigDecimal.valueOf(value);
            default -> throw new IllegalArgumentException(
                    "不支持的 Number 类型: "
                            + number.getClass().getName()
            );
        };
    }
}