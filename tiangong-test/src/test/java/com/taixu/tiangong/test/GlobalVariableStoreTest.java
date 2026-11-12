package com.taixu.tiangong.test;

import org.junit.jupiter.api.Test;
import com.taixu.tiangong.core.agent.runtime.GlobalVariableStore;
import com.taixu.tiangong.core.agent.runtime.SingletonGlobalVariableStore;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GlobalVariableStore 信号总线测试。<br>
 * 覆盖：put/get/consume/latest/remove/contains、类型不匹配异常。
 */
class GlobalVariableStoreTest {

    @Test
    void putAndGet() {
        GlobalVariableStore store = new SingletonGlobalVariableStore();
        store.put("key1", "hello");
        Optional<String> val = store.get("key1", String.class);
        assertTrue(val.isPresent());
        assertEquals("hello", val.get());
    }

    @Test
    void getMissingKeyReturnsEmpty() {
        GlobalVariableStore store = new SingletonGlobalVariableStore();
        assertTrue(store.get("missing", String.class).isEmpty());
    }

    @Test
    void consumeRemovesKey() {
        GlobalVariableStore store = new SingletonGlobalVariableStore();
        store.put("signal", 42);
        Optional<Integer> val = store.consume("signal", Integer.class);
        assertEquals(42, val.orElseThrow());
        assertFalse(store.contains("signal"));
    }

    @Test
    void latestDoesNotRemove() {
        GlobalVariableStore store = new SingletonGlobalVariableStore();
        store.put("status", "RUNNING");
        store.latest("status", String.class);
        assertTrue(store.contains("status"));
    }

    @Test
    void removeAndContains() {
        GlobalVariableStore store = new SingletonGlobalVariableStore();
        store.put("k", "v");
        assertTrue(store.contains("k"));
        store.remove("k");
        assertFalse(store.contains("k"));
    }

    @Test
    void typeMismatchThrows() {
        GlobalVariableStore store = new SingletonGlobalVariableStore();
        store.put("num", 42);
        assertThrows(IllegalStateException.class,
                () -> store.get("num", String.class),
                "类型不匹配应抛异常");
    }

    @Test
    void overwriteValue() {
        GlobalVariableStore store = new SingletonGlobalVariableStore();
        store.put("k", "v1");
        store.put("k", "v2");
        assertEquals("v2", store.get("k", String.class).orElseThrow());
    }
}
