package org.wutong.tiangong.core.storage;

import org.wutong.tiangong.annotations.annotation.framework.field.StorageFields;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class TestStorageRecord implements StorageRecord<Long> {

    @StorageFields(name = "id")
    private final Long id;

    @Override
    public Long getId() {
        return id;
    }

}
