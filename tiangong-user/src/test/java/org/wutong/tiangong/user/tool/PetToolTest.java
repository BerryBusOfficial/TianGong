package org.wutong.tiangong.user.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wutong.tiangong.user.memory.PetMemory;
import org.wutong.tiangong.user.model.Mood;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PetToolTest {

    @TempDir
    Path dir;

    private PetMemory memory;
    private PetTool tool;

    @BeforeEach
    void setUp() {
        memory = new PetMemory();
        memory.setMemoryPath(dir.resolve("memory.json"));
        tool = new PetTool();
        tool.setMemory(memory);
    }

    @Test
    @DisplayName("记事实：写入后可取回")
    void remember() {
        String result = tool.rememberFact("称呼", "梧桐");

        assertTrue(result.contains("梧桐"));
        assertEquals("梧桐", memory.recall("称呼"));
        assertTrue(tool.recallFacts().contains("梧桐"));
    }

    @Test
    @DisplayName("空 key / 空 value 不写库，只回一句话")
    void rejectsBlankInput() {
        assertTrue(tool.rememberFact(" ", "x").contains("没记住"));
        assertTrue(tool.rememberFact("k", "  ").contains("没记住"));
        assertTrue(memory.facts().isEmpty(), "非法输入不应写入任何事实");
        assertEquals("目前还没记住关于你的事。", tool.recallFacts(), "没有事实时应给一句可说的话");
    }

    @Test
    @DisplayName("忘事实：记过的能忘掉，没记过的也别报错")
    void forget() {
        tool.rememberFact("饮料", "拿铁");

        assertTrue(tool.forgetFact("饮料").contains("忘掉啦"));
        assertTrue(tool.forgetFact("饮料").contains("本来就没记过"));
        assertNull(memory.recall("饮料"));
    }

    @Test
    @DisplayName("心情解析：中文名与枚举名都收，认不出的返回 null")
    void parseMood() {
        assertEquals(Mood.HAPPY, PetTool.parseMood("开心"));
        assertEquals(Mood.GRUMPY, PetTool.parseMood("闹别扭"));
        assertEquals(Mood.SLEEPY, PetTool.parseMood("sleepy"), "模型可能直接输出枚举名");
        assertNull(PetTool.parseMood("暴躁"), "没有这个心情");
        assertNull(PetTool.parseMood(null));
    }

    @Test
    @DisplayName("切心情：认得出的真的切，认不出的列出可选项且不动状态")
    void setMood() {
        assertTrue(tool.setMood("兴奋").contains("兴奋"));
        assertEquals(Mood.EXCITED, memory.state().getMood(), "切心情应真的写进状态");

        String rejected = tool.setMood("暴躁");
        assertTrue(rejected.contains("开心"), "拒绝时要告诉模型有哪些可选");
        assertEquals(Mood.EXCITED, memory.state().getMood(), "认不出的心情不该把状态改成 null");
    }
}
