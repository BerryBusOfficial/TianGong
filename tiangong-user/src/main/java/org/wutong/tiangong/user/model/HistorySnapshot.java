package org.wutong.tiangong.user.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 历史的落盘结构。<br>
 * 与 {@link MemorySnapshot} 同理：持久化结构独立于运行期对象，
 * 老文件缺字段时取默认值而非解析失败。
 */
@Data
public class HistorySnapshot {

    /** 会话标识：跨重启沿用，便于把新记录续在同一段历史后面 */
    private String sessionId = "";
    private List<HistoryItem> items = new ArrayList<>();

    public List<HistoryItem> safeItems() {
        return items == null ? new ArrayList<>() : items;
    }
}
