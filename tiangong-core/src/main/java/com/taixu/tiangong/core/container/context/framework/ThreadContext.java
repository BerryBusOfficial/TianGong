package com.taixu.tiangong.core.container.context.framework;

import lombok.Getter;
import com.taixu.tiangong.common.enums.Namespace;
import com.taixu.tiangong.common.enums.ResourceType;
import com.taixu.tiangong.common.util.IdGenerator;
import com.taixu.tiangong.core.storage.MapStorage;
import com.taixu.tiangong.core.storage.Storage;
import com.taixu.tiangong.core.storage.StorageRecord;

/**
 * 线程上下文，用于存储框架内所有线程中需要临时存放的上下文信息。
 */
public final class ThreadContext implements FrameworkContext {

    @Getter
    private final String id = IdGenerator.generate(Namespace.SESSION, ResourceType.CONTEXT);

    private final Storage<?, ?> storage = new MapStorage<>();

    @Override
    @SuppressWarnings("unchecked")
    public <ID, R extends StorageRecord<ID>> Storage<R, ID> getStorage() {
        return (Storage<R, ID>) storage;
    }
}