package com.taixu.tiangong.skill.runtime;

import com.taixu.tiangong.common.agent.context.AgentContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.taixu.tiangong.common.exception.SystemRuntimeException;
import com.taixu.tiangong.common.sandbox.ExecOptions;
import com.taixu.tiangong.common.sandbox.ExecResult;
import com.taixu.tiangong.core.sandbox.SandboxRegistry;
import com.taixu.tiangong.core.script.ScriptRuntime;
import com.taixu.tiangong.core.script.ScriptRuntimeRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 脚本运行时验证。<br>
 * <br>
 * 这里锁的是 L3（脚本执行）最关键的一条性质：<b>没有工具链时必须明确说没有</b>。
 * 一旦运行时谎报能力，模型会以为脚本已经执行过 —— 这类错误在运行日志里
 * 表现为「一切正常」，排查成本极高。
 */
class ScriptRuntimeTest {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    @Test
    @DisplayName("后缀归一化：大小写与前导点不影响解析")
    void normalizesExtension() {
        ScriptRuntimeRegistry registry = new ScriptRuntimeRegistry();
        registry.register(new FakeRuntime("fake", List.of("SH", ".bat")));

        assertTrue(registry.resolve("sh").isPresent());
        assertTrue(registry.resolve(".SH").isPresent());
        assertTrue(registry.resolve("bat").isPresent());
    }

    @Test
    @DisplayName("没有可用运行时时 require 抛明确异常，绝不静默跳过")
    void requireThrowsWhenMissing() {
        ScriptRuntimeRegistry registry = new ScriptRuntimeRegistry();

        SystemRuntimeException e = assertThrows(SystemRuntimeException.class,
                () -> registry.require("ts"));

        assertTrue(e.getMessage().contains("ts"),
                "错误信息必须点名是哪个后缀没运行时，否则无从判断缺的是什么工具链");
    }

    @Test
    @DisplayName("同一后缀先注册者优先，后来者不覆盖（避免随扫描顺序漂移）")
    void firstRegistrationWins() {
        ScriptRuntimeRegistry registry = new ScriptRuntimeRegistry();
        registry.register(new FakeRuntime("first", List.of("ts")));
        registry.register(new FakeRuntime("second", List.of("ts")));

        assertEquals("first", registry.require("ts").name());
    }

    @Test
    @DisplayName("Shell 运行时真实执行脚本（走沙箱，不裸跑）")
    void shellRuntimeExecutesScript(@TempDir Path dir) throws IOException {
        ShellScriptRuntime runtime = new ShellScriptRuntime();
        runtime.sandboxes = new SandboxRegistry();

        Path script = WINDOWS
                ? dir.resolve("hello.bat")
                : dir.resolve("hello.sh");
        Files.writeString(script,
                WINDOWS ? "@echo off\r\necho hello-from-script"
                        : "#!/bin/sh\necho hello-from-script",
                StandardCharsets.UTF_8);
        if (!WINDOWS) {
            assertTrue(script.toFile().setExecutable(true), "POSIX 下需给脚本执行位");
        }

        ExecResult result = runtime.run(script, List.of(), null, ExecOptions.none());

        assertTrue(result.success(), "脚本应执行成功，实际: " + result);
        assertTrue(result.output().contains("hello-from-script"),
                "应拿到脚本输出，实际: " + result.output());
    }

    @Test
    @DisplayName("Node 运行时：环境没有 npx 就一个后缀都不接管")
    void nodeRuntimeDeclinesWhenToolchainMissing() {
        NodeScriptRuntime runtime = new NodeScriptRuntime();
        boolean npxPresent = ExecutableLocator.find("npx").isPresent();

        List<String> exts = runtime.supportedExtensions();

        if (npxPresent) {
            assertTrue(exts.contains("ts"), "有 npx 时应接管 ts");
        } else {
            assertTrue(exts.isEmpty(),
                    "没有 npx 时绝不能接管 ts —— 接管了就等于向模型谎报能力");
        }
    }

    @Test
    @DisplayName("探测不到可执行文件时返回空，不抛异常")
    void locatorReturnsEmptyForMissingBinary() {
        assertTrue(ExecutableLocator.find("definitely-not-a-real-binary-xyz").isEmpty());
    }

    // ==================== 测试用运行时 ====================

    private record FakeRuntime(String name, List<String> extensions) implements ScriptRuntime {

        @Override
        public List<String> supportedExtensions() {
            return extensions;
        }

        @Override
        public ExecResult run(Path script, List<String> args,
                              AgentContext context,
                              ExecOptions options) {
            return ExecResult.failed(name, "测试桩不执行", 0);
        }

        @Override
        public String name() {
            return name;
        }
    }
}
