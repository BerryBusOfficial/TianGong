package com.taixu.tiangong.annotations.processor.registry;

import java.util.Collection;
import java.util.Optional;

/**
 * 注册表接口，提供按名称注册、查找、遍历元素的能力。
 */
public interface Registry<E> extends Iterable<E> {

    void register(E element);

    void unregister(String name);

    Optional<E> get(String name);

    Collection<E> values();

    boolean contains(String name);

    int size();

}
