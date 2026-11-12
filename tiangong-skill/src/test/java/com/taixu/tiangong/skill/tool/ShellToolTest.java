package com.taixu.tiangong.skill.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.taixu.tiangong.core.sandbox.SandboxRegistry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 宿主工具验证。<br>
 * <br>
 * 两条必须钉死的性质：
 * <ol>
 *     <li>{@code Bash} <b>真的能执行</b> —— 技能正文里那句「跑这条命令」的价值全在这里。
 *         只测参数校验而没真跑过，等于什么都没证明</li>
 *     <li>{@code Read} 的边界（目录 / 不存在 / 非法）必须返回<b>可理解的文字</b>而不是抛异常 ——
 *         返回值直接回灌给模型，抛异常会让整轮对话失败</li>
 * </ol>
 */
class ShellToolTest {

    private static ShellTool tool() {
        ShellTool tool = new ShellTool();
        tool.sandboxes = new SandboxRegistry();
        return tool;
    }

    @Test
    @DisplayName("Bash 真的能把命令跑起来并返回输出与退出码")
    void bashReallyExecutes() {
        String out = tool().bash("echo tiangong-l2-ok");

        assertTrue(out.contains("退出码 0"), "期望成功退出，实际: " + out);
        assertTrue(out.contains("tiangong-l2-ok"), "输出里应带回显内容，实际: " + out);
    }

    @Test
    @DisplayName("空命令直接拒绝，不交给沙箱")
    void rejectsBlankCommand() {
        assertEquals("命令为空", tool().bash("   "));
    }

    @Test
    @DisplayName("读文件返回内容")
    void readsFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "hello skill", StandardCharsets.UTF_8);

        assertEquals("hello skill", tool().read(file.toString()));
    }

    @Test
    @DisplayName("目录 / 不存在 / 非法路径都返回可读提示，不抛异常")
    void readEdgeCasesReturnText(@TempDir Path dir) {
        ShellTool tool = tool();

        assertTrue(tool.read(dir.toString()).contains("目录"));
        assertTrue(tool.read(dir.resolve("nope.txt").toString()).contains("不存在"));

        // NUL 在 POSIX 与 Windows 上都是非法路径字符，不依赖平台特有规则
        String invalid = tool.read("bad\u0000name");
        assertTrue(invalid.contains("路径非法"), "非法路径必须给出明确提示，实际: " + invalid);
    }

    @Test
    @DisplayName("超过上限的文件直接拒绝，不把大文件灌进上下文")
    void rejectsOversizedFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("big.txt");
        Files.writeString(file, "x".repeat(ShellTool.MAX_READ_BYTES + 1), StandardCharsets.UTF_8);

        assertTrue(tool().read(file.toString()).contains("过大"));
    }
}
