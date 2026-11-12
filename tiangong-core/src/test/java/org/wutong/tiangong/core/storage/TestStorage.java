package org.wutong.tiangong.core.storage;

import org.junit.jupiter.api.Test;

import java.util.Random;

public class TestStorage {

    @Test
    public void testStorage() {
        TestStorageRecord record = new TestStorageRecord(new Random().nextLong(100000L, 1000000L));
    }

}
