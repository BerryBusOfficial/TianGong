package com.taixu.tiangong.core.storage.query;

import com.taixu.tiangong.core.storage.StorageRecord;

import java.util.function.Function;

public record Field<R extends StorageRecord<?>, V>(
        String name,
        Function<R, V> getter
) {

    public static <R extends StorageRecord<?>, V> Field<R, V> of(
            String name,
            Function<R, V> getter
    ) {
        return new Field<>(name, getter);
    }

    public V get(R record) {
        return getter.apply(record);
    }
}