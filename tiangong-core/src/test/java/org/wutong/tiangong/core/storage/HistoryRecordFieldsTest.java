package org.wutong.tiangong.core.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 {@code @StorageRecords} 处理器生成的 {@code HistoryRecordFields}。<br>
 * <br>
 * <b>这里刻意直接引用生成的类名</b>，不走反射：这个处理器曾经因为
 * {@code @SupportedAnnotationTypes} 里包名写错（{@code com.wutong} vs {@code org.wutong}）
 * 而<b>从来没运行过</b>——包名不对不会报错，只是「说好的类没生成」，
 * 光看 BUILD SUCCESS 完全发现不了。直接引用后，它再哑掉就是<b>编译错误</b>。
 */
class HistoryRecordFieldsTest {

    @Test
    @DisplayName("查询字段名取自 @StorageFields 的 value / 组件名")
    void fieldNames() {
        assertEquals("history_key", HistoryRecordFields.HISTORY_KEY.name());
        assertEquals("value", HistoryRecordFields.VALUE.name(),
                "@StorageFields 没给值时退回组件名");
    }

    @Test
    @DisplayName("getter 指向 record 组件，能取回原始值")
    void getters() {
        BaseHistoryItem item = new BaseHistoryItem() {
        };
        HistoryRecord<BaseHistoryItem> record = new HistoryRecord<>("k1", item);

        assertEquals("k1", HistoryRecordFields.HISTORY_KEY.get(record));
        assertSame(item, HistoryRecordFields.VALUE.get(record));
    }
}
