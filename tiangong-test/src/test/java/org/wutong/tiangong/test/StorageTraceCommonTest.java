package org.wutong.tiangong.test;

import org.junit.jupiter.api.Test;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.entrance.ApiEntranceNode;
import org.wutong.tiangong.annotations.processor.util.AliasUtils;
import org.wutong.tiangong.common.exception.SystemRuntimeException;
import org.wutong.tiangong.common.util.JsonUtil;
import org.wutong.tiangong.core.storage.MapStorage;
import org.wutong.tiangong.core.storage.StorageRecord;
import org.wutong.tiangong.core.storage.query.Field;
import org.wutong.tiangong.core.storage.query.StorageQuery;
import org.wutong.tiangong.core.storage.query.condition.Conditions;
import org.wutong.tiangong.core.trace.TraceHandler;
import org.wutong.tiangong.core.trace.TraceHandlerRegistry;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Storage / Trace / Common 工具测试。<br>
 * 覆盖：MapStorage CRUD、StorageQuery 过滤/排序/分页、TraceHandlerRegistry 注册/查找、
 * JsonUtil 序列化/反序列化、AliasUtils 别名解析。
 */
class StorageTraceCommonTest {

    // ==================== MapStorage ====================

    @Test
    void insertAndFindById() {
        MapStorage<TestRecord, String> storage = new MapStorage<>();
        TestRecord record = new TestRecord("1", "Alice", 30);
        storage.insert(record);
        Optional<TestRecord> found = storage.findById("1");
        assertTrue(found.isPresent());
        assertEquals("Alice", found.get().name());
    }

    @Test
    void insertDuplicateThrows() {
        MapStorage<TestRecord, String> storage = new MapStorage<>();
        storage.insert(new TestRecord("1", "Alice", 30));
        assertThrows(SystemRuntimeException.class,
                () -> storage.insert(new TestRecord("1", "Bob", 25)),
                "重复 ID 插入应抛异常");
    }

    @Test
    void insertAll() {
        MapStorage<TestRecord, String> storage = new MapStorage<>();
        Collection<TestRecord> inserted = storage.insertAll(List.of(
                new TestRecord("1", "Alice", 30),
                new TestRecord("2", "Bob", 25)
        ));
        assertEquals(2, inserted.size());
        assertEquals(2, storage.count(StorageQuery.<TestRecord>builder().build()));
    }

    @Test
    void updateExistingRecord() {
        MapStorage<TestRecord, String> storage = new MapStorage<>();
        storage.insert(new TestRecord("1", "Alice", 30));
        storage.update(new TestRecord("1", "Alice2", 31));
        TestRecord updated = storage.findById("1").orElseThrow();
        assertEquals("Alice2", updated.name());
        assertEquals(31, updated.age());
    }

    @Test
    void updateNonExistentThrows() {
        MapStorage<TestRecord, String> storage = new MapStorage<>();
        assertThrows(SystemRuntimeException.class,
                () -> storage.update(new TestRecord("99", "Ghost", 0)),
                "更新不存在的记录应抛异常");
    }

    @Test
    void deleteById() {
        MapStorage<TestRecord, String> storage = new MapStorage<>();
        storage.insert(new TestRecord("1", "Alice", 30));
        assertTrue(storage.deleteById("1"));
        assertTrue(storage.findById("1").isEmpty());
        assertFalse(storage.deleteById("1"), "再次删除应返回 false");
    }

    @Test
    void clearRemovesAllRecords() {
        MapStorage<TestRecord, String> storage = new MapStorage<>();
        storage.insert(new TestRecord("1", "Alice", 30));
        storage.insert(new TestRecord("2", "Bob", 25));
        storage.clear();
        assertEquals(0, storage.count(StorageQuery.<TestRecord>builder().build()));
    }

    @Test
    void countWithCondition() {
        MapStorage<TestRecord, String> storage = new MapStorage<>();
        storage.insert(new TestRecord("1", "Alice", 30));
        storage.insert(new TestRecord("2", "Bob", 25));
        storage.insert(new TestRecord("3", "Carol", 35));
        long count = storage.count(StorageQuery.<TestRecord>builder()
                .where(Conditions.gt(Field.of("age", TestRecord::age), 28))
                .build());
        assertEquals(2, count, "age > 28 的记录应有 2 条");
    }

    @Test
    void findWithPagination() {
        MapStorage<TestRecord, String> storage = new MapStorage<>();
        for (int i = 1; i <= 10; i++) {
            storage.insert(new TestRecord(String.valueOf(i), "name" + i, i));
        }
        Collection<TestRecord> page = storage.find(StorageQuery.<TestRecord>builder()
                .limit(3)
                .build());
        assertEquals(3, page.size(), "limit 3 应返回 3 条");
    }

    @Test
    void existsCheck() {
        MapStorage<TestRecord, String> storage = new MapStorage<>();
        storage.insert(new TestRecord("1", "Alice", 30));
        assertTrue(storage.exists(StorageQuery.<TestRecord>builder()
                .where(Conditions.eq(Field.of("name", TestRecord::name), "Alice"))
                .build()));
        assertFalse(storage.exists(StorageQuery.<TestRecord>builder()
                .where(Conditions.eq(Field.of("name", TestRecord::name), "Bob"))
                .build()));
    }

    // ==================== TraceHandlerRegistry ====================

    @Test
    void traceHandlerRegisterAndGet() {
        TraceHandlerRegistry registry = new TraceHandlerRegistry();
        TestTraceHandler handler = new TestTraceHandler();
        registry.register(handler);
        assertEquals(1, registry.size());
        assertTrue(registry.contains(handler.getClass().getName()));
    }

    @Test
    void traceHandlerUnregister() {
        TraceHandlerRegistry registry = new TraceHandlerRegistry();
        TestTraceHandler handler = new TestTraceHandler();
        registry.register(handler);
        registry.unregister(handler.getClass().getName());
        assertEquals(0, registry.size());
        assertFalse(registry.contains(handler.getClass().getName()));
    }

    @Test
    void traceHandlerValuesReturnsAll() {
        TraceHandlerRegistry registry = new TraceHandlerRegistry();
        registry.register(new TestTraceHandler());
        registry.register(new AnotherTraceHandler());
        Collection<TraceHandler> values = registry.values();
        assertEquals(2, values.size());
    }

    // ==================== JsonUtil ====================

    @Test
    void toJsonAndFromJson() throws Exception {
        TestRecord record = new TestRecord("1", "Alice", 30);
        String json = JsonUtil.toJson(record);
        assertNotNull(json);
        TestRecord restored = JsonUtil.fromJson(json, TestRecord.class);
        assertEquals(record.getId(), restored.getId());
        assertEquals(record.name(), restored.name());
    }

    @Test
    void convertToMap() {
        TestRecord record = new TestRecord("1", "Alice", 30);
        Map<String, Object> map = JsonUtil.convertToMap(record);
        assertNotNull(map);
        // JsonUtil uses SNAKE_CASE, so id stays as "id"
        assertEquals("1", map.get("id"));
    }

    @Test
    void convertToBean() {
        Map<String, Object> map = Map.of("id", "2", "name", "Bob", "age", 25);
        TestRecord bean = JsonUtil.convertToBean(map, TestRecord.class);
        assertEquals("2", bean.getId());
        assertEquals("Bob", bean.name());
    }

    // ==================== AliasUtils ====================

    @Test
    @ApiEntranceNode(route = "/test")
    void aliasUtilsResolvesValueToName() throws Exception {
        // @ApiEntranceNode 有 @Alias("name") on value() 和 @Alias("value") on name()
        ApiEntranceNode ann = StorageTraceCommonTest.class
                .getDeclaredMethod("aliasUtilsResolvesValueToName")
                .getAnnotation(ApiEntranceNode.class);
        Map<String, Object> attrs = AliasUtils.resolveAttributes(ann);
        // value 和 name 别名应同步
        String value = (String) attrs.get("value");
        String name = (String) attrs.get("name");
        assertEquals(value, name, "value 和 name 别名应同步");
    }

    // ==================== 测试用类型 ====================

    record TestRecord(String id, String name, int age) implements StorageRecord<String> {
        @Override
        public String getId() { return id; }
    }

    static class TestTraceHandler implements TraceHandler {
        // 使用默认空实现
    }

    static class AnotherTraceHandler implements TraceHandler {
        // 使用默认空实现
    }
}
