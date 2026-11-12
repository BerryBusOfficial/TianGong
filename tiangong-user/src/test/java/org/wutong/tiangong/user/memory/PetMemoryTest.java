package org.wutong.tiangong.user.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wutong.tiangong.user.model.Mood;

import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 记忆的持久化与演化。<br>
 * <b>不起真实容器</b>（tiangong-user 标了 {@code @EnableAgentLoop}，起容器会触发 START 轮询
 * 并永久阻塞在 STDIN），全部按 POJO 装配。
 */
class PetMemoryTest {

    @TempDir
    Path dir;

    private PetMemory fresh(String name) {
        PetMemory memory = new PetMemory();
        memory.setMemoryPath(dir.resolve(name));
        return memory;
    }

    @Test
    @DisplayName("记住的事实能取回；同 key 覆盖而不是追加")
    void rememberAndRecall() {
        PetMemory memory = fresh("memory.json");

        memory.remember("称呼", "梧桐");
        assertEquals("梧桐", memory.recall("称呼"));

        memory.remember("称呼", "老大");
        assertEquals("老大", memory.recall("称呼"), "同 key 应覆盖，保留旧值会让偏好变化失效");
        assertEquals(1, memory.facts().size(), "覆盖不应产生第二条记录");
    }

    @Test
    @DisplayName("忘掉：存在的返回旧值，不存在的返回 null")
    void forget() {
        PetMemory memory = fresh("memory.json");
        memory.remember("饮料", "拿铁");

        assertEquals("拿铁", memory.forget("饮料"));
        assertNull(memory.forget("饮料"), "已忘掉的事实再忘一次应返回 null");
        assertNull(memory.recall("饮料"));
    }

    @Test
    @DisplayName("记忆跨实例存活：换一个 PetMemory 读同一文件仍取得到")
    void persistsAcrossInstances() {
        PetMemory first = fresh("memory.json");
        first.remember("职业", "工程师");

        PetMemory second = fresh("memory.json");
        assertEquals("工程师", second.recall("职业"), "落盘失败会让重启即失忆");
    }

    @Test
    @DisplayName("一轮结束：扣精力、涨好感、轮数 +1")
    void afterTurnEvolvesState() {
        PetMemory memory = fresh("memory.json");
        LocalDateTime now = LocalDateTime.now();

        memory.afterTurn(now);

        assertEquals(1, memory.state().getTotalTurns());
        assertEquals(78, memory.state().getEnergy(), "每轮固定扣 2 点精力");
        assertEquals(51, memory.state().getAffinity(), "每轮固定涨 1 点好感");
    }

    @Test
    @DisplayName("空窗后回来会恢复精力：数值要能解释，不能凭空涨")
    void recoversEnergyWhenIdle() {
        PetMemory memory = fresh("memory.json");
        LocalDateTime t0 = LocalDateTime.now();

        memory.afterTurn(t0.minusHours(2));
        assertEquals(78, memory.state().getEnergy());

        memory.afterTurn(t0);
        // 空窗 2 小时 → 恢复 20，再扣本轮 2 → 96
        assertEquals(96, memory.state().getEnergy(), "恢复量应由空窗时长算出");
    }

    @Test
    @DisplayName("精力见底时心情自动转困倦")
    void turnsSleepyWhenDrained() {
        PetMemory memory = fresh("memory.json");
        memory.setMood(Mood.HAPPY);

        for (int i = 0; i < 40; i++) {
            memory.afterTurn(LocalDateTime.now());
        }

        assertEquals(Mood.SLEEPY, memory.state().getMood(), "状态之间要有联动，否则数值只是摆设");
    }

    @Test
    @DisplayName("事实数超上限淘汰最久未更新的那条")
    void evictsOldestWhenOverCap() {
        PetMemory memory = fresh("memory.json");
        for (int i = 0; i < PetMemory.MAX_FACTS + 5; i++) {
            memory.remember("k" + i, "v" + i);
        }

        assertEquals(PetMemory.MAX_FACTS, memory.facts().size(), "无上限会让提示词被事实淹没");
        assertNull(memory.recall("k0"), "淘汰应从最旧的一条开始");
        assertNotNull(memory.recall("k" + (PetMemory.MAX_FACTS + 4)));
    }

    @Test
    @DisplayName("坏文件不致命：读不出来就按空状态继续")
    void brokenFileFallsBackToEmpty() throws Exception {
        Path path = dir.resolve("broken.json");
        java.nio.file.Files.writeString(path, "{ 这不是 json");

        PetMemory memory = new PetMemory();
        memory.setMemoryPath(path);

        assertTrue(memory.facts().isEmpty(), "落盘文件被改坏不应让应用起不来");
        assertEquals(0, memory.state().getTotalTurns());
    }
}
