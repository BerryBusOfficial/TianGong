package org.wutong.tiangong.user.history;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wutong.tiangong.llm.message.HumanMessage;
import org.wutong.tiangong.user.model.HistoryItem;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PetHistoryTest {

    @TempDir
    Path dir;

    private PetHistory fresh(String name) {
        PetHistory history = new PetHistory();
        history.setHistoryPath(dir.resolve(name));
        return history;
    }

    @Test
    @DisplayName("按 sessionId 记录：两条链的发言不会混在一起")
    void recordsWithSessionId() {
        PetHistory history = fresh("history.json");

        history.add("session-a", new HumanMessage("来自 A"));
        history.add("session-b", new HumanMessage("来自 B"));
        history.add("session-a", new HumanMessage("A 又说了一句"));

        List<HistoryItem> a = history.recent(10).stream()
                .filter(i -> "session-a".equals(i.getSessionId()))
                .toList();
        assertEquals(2, a.size(), "sessionId 由上下文提供，用于区分两条链");
    }

    @Test
    @DisplayName("recent(n) 取最新的 n 条，顺序保持写入顺序")
    void recentKeepsOrder() {
        PetHistory history = fresh("history.json");
        for (int i = 1; i <= 5; i++) {
            history.add("s", new HumanMessage("第" + i + "句"));
        }

        List<HistoryItem> recent = history.recent(2);
        assertEquals(2, recent.size());
        assertEquals("第4句", recent.get(0).getContent());
        assertEquals("第5句", recent.get(1).getContent(), "末尾应为最新");
    }

    @Test
    @DisplayName("历史跨实例存活")
    void persistsAcrossInstances() {
        PetHistory first = fresh("history.json");
        first.add("s", new HumanMessage("记住我"));
        first.save();

        PetHistory second = fresh("history.json");
        assertEquals(1, second.size());
        assertEquals("记住我", second.recent(1).get(0).getContent());
    }

    @Test
    @DisplayName("落盘时裁到上限：文件不能无限长")
    void trimsOnSave() {
        PetHistory history = fresh("history.json");
        for (int i = 0; i < PetHistory.MAX_ITEMS + 20; i++) {
            history.add("s", new HumanMessage("m" + i));
        }
        history.save();

        PetHistory reloaded = fresh("history.json");
        assertEquals(PetHistory.MAX_ITEMS, reloaded.size(), "超出上限应淘汰最旧的");
        assertEquals("m" + (PetHistory.MAX_ITEMS + 19), reloaded.recent(1).get(0).getContent());
    }
}
