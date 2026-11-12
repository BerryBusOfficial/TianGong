package com.taixu.tiangong.core.container.context.framework;

import com.taixu.tiangong.core.storage.Storage;
import com.taixu.tiangong.core.storage.StorageRecord;

/**
 * 框架级上下文接口，外部无法实现和使用
 */
public sealed interface FrameworkContext permits
        ApplicationFrameworkContext,
        ThreadContext {

    /**
     * 泛型方法：调用方按需要的记录类型获取存储，避免外部强转。<br>
     * 例：{@code Storage<HistoryRecord<?>, String> s = ctx.getStorage();}
     */
    <ID, R extends StorageRecord<ID>> Storage<R, ID> getStorage();

}
