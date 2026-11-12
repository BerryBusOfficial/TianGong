package org.wutong.tiangong.core.storage;

import org.wutong.tiangong.core.storage.query.StorageQuery;

import java.util.Collection;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 通用记录存储接口。
 *
 * <p>Storage 面向记录提供类似数据库 CRUD 的抽象，
 * 具体实现可以基于内存、SQL 数据库、Redis 等存储介质。</p>
 *
 * @param <R>  记录类型
 * @param <ID> 记录唯一标识类型
 */
public interface Storage<R extends StorageRecord<ID>, ID> {

    // =========================
    // INSERT
    // =========================

    R insert(R record);

    Collection<R> insertAll(Collection<R> records);


    // =========================
    // SELECT
    // =========================

    Optional<R> findById(ID id);

    Collection<R> find(StorageQuery<R> query);

    Stream<R> stream(StorageQuery<R> query);

    long count(StorageQuery<R> query);

    boolean exists(StorageQuery<R> query);


    // =========================
    // UPDATE
    // =========================

    R update(R record);


    // =========================
    // DELETE
    // =========================

    boolean deleteById(ID id);

    long delete(StorageQuery<R> query);


    // =========================
    // LIFECYCLE
    // =========================

    void clear();
}