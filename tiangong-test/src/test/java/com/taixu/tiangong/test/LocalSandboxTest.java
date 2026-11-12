package com.taixu.tiangong.test;

import com.taixu.tiangong.common.agent.context.AgentContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.taixu.tiangong.common.sandbox.CommandLine;
import com.taixu.tiangong.common.sandbox.ExecOptions;
import com.taixu.tiangong.common.sandbox.ExecResult;
import com.taixu.tiangong.common.sandbox.Sandbox;
import com.taixu.tiangong.core.sandbox.LocalSandbox;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link LocalSandbox} 冒烟测试：验证四项强制保障在真机上确实生效。<br>
 * <br>
 * 这四项（超时杀进程树 / 输出限流 / 协作式取消 / 流排空防死锁）如果写错，
 * 症状往往是「某些情况下卡住」或「内存慢慢涨」，<b>在正常路径上完全看不出来</b>。
 * 因此必须有真实进程参与的测试，而不是只测 API 形状。<br>
 * <br>
 * 注意：本测试<b>会真实起进程</b>，依赖当前平台的基础命令
 * （Windows 用 {@code cmd}，POSIX 用 {@code sh}）。
 */
class LocalSandboxTest {

    private final LocalSandbox sandbox = new LocalSandbox();
    private final TestContext context = new TestContext();

    @Test
    @DisplayName("基本执行：能拿到 stdout 与退出码 0")
    void basicExecution() {
        CommandLine cmd = CommandLine.shell("echo hello-tiangong");

        ExecResult r = sandbox.exec(cmd, context, ExecOptions.builder()
                .timeout(Duration.ofSeconds(10))
                .build());

        assertFalse(r.timedOut(), "不应超时");
        assertFalse(r.cancelled(), "不应被取消");
        assertTrue(r.output().contains("hello-tiangong"),
                "输出应包含回显内容，实际: [" + r.output() + "]");
        assertEquals(0, r.exitCode().orElse(-1), "退出码应为 0");
        assertTrue(r.success(), "应判定为成功");
        assertEquals(LocalSandbox.NAME, r.sandboxName());
    }

    @Test
    @DisplayName("非零退出码：不抛异常，作为业务结果返回")
    void nonZeroExitCodeDoesNotThrow() {
        // exit 3 —— 命令成功执行但返回非 0，属于「命令的结果」而非「沙箱故障」
        CommandLine cmd = CommandLine.shell("exit 3");

        ExecResult r = assertDoesNotThrow(
                () -> sandbox.exec(cmd, context, ExecOptions.builder()
                        .timeout(Duration.ofSeconds(10)).build()),
                "非零退出码不应抛异常");

        assertEquals(3, r.exitCode().orElse(-1));
        assertFalse(r.success(), "退出码非 0 不应判定为成功");
        assertFalse(r.timedOut());
    }

    @Test
    @DisplayName("超时：必须终止并返回 timedOut，不得无限等待")
    void timeoutKillsProcess() {
        // 睡 30 秒，但只给 1 秒超时
        String sleepScript = CommandLine.isWindows() ? "ping -n 30 127.0.0.1 > nul" : "sleep 30";
        CommandLine cmd = CommandLine.shell(sleepScript);

        long start = System.currentTimeMillis();
        ExecResult r = sandbox.exec(cmd, context, ExecOptions.builder()
                .timeout(Duration.ofSeconds(1))
                .build());
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(r.timedOut(), "应标记为超时");
        assertTrue(r.exitCode().isEmpty(), "超时进程无退出码");
        // 关键：确实被打断了，而不是等到命令自然结束。留 3 秒余量容纳 taskkill 开销
        assertTrue(elapsed < 5000,
                "应在超时后很快返回，实际耗时 " + elapsed + "ms（疑似未杀进程、等到了自然结束）");
    }

    @Test
    @DisplayName("协作式取消：cancel 后应在轮询粒度内终止")
    void cancellationResponds() throws Exception {
        String sleepScript = CommandLine.isWindows() ? "ping -n 30 127.0.0.1 > nul" : "sleep 30";
        CommandLine cmd = CommandLine.shell(sleepScript);

        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<ExecResult> holder = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread worker = new Thread(() -> {
            try {
                started.countDown();
                holder.set(sandbox.exec(cmd, context, ExecOptions.builder()
                        .timeout(Duration.ofSeconds(60))   // 超时给足，确保是「取消」而非「超时」生效
                        .build()));
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        worker.start();

        assertTrue(started.await(5, TimeUnit.SECONDS), "工作线程应已启动");
        Thread.sleep(500);          // 让命令真正跑起来
        context.cancel();           // 触发取消

        worker.join(5000);
        assertFalse(worker.isAlive(), "取消后工作线程应在数秒内退出");

        assertNull(failure.get(), "不应抛异常: " + failure.get());
        ExecResult r = holder.get();
        assertNotNull(r, "应产出结果");
        assertTrue(r.cancelled(), "应标记为被取消");
        assertFalse(r.timedOut(), "不应被标记为超时（超时给了 60s）");
    }

    @Test
    @DisplayName("输出限流：超长输出被截断且标记 truncated")
    void outputIsTruncated() {
        // 生成远超上限的输出（Windows: for /L，POSIX: seq + yes）
        String bigOutput = CommandLine.isWindows()
                ? "for /L %i in (1,1,20000) do @echo 0123456789012345678901234567890123456789"
                : "seq 1 20000 | awk '{print \"0123456789012345678901234567890123456789\"}'";
        CommandLine cmd = CommandLine.shell(bigOutput);
        int limit = 4096;

        ExecResult r = sandbox.exec(cmd, context, ExecOptions.builder()
                .timeout(Duration.ofSeconds(30))
                .maxOutputBytes(limit)
                .build());

        assertTrue(r.truncated(), "应标记为已截断");
        assertTrue(r.totalBytes() > limit,
                "原始总字节数应超过上限，实际 " + r.totalBytes());
        // 允许轻微超出（单块读取粒度），但不应接近原始规模
        assertTrue(r.output().getBytes(StandardCharsets.UTF_8).length < limit * 2,
                "保留内容应被限制在上限附近，实际 " + r.output().getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    @DisplayName("direct 模式：argv 不经 shell，竖线作为字面参数传入")
    void directModeDoesNotInterpretShellSyntax() throws Exception {
        // 用真实的独立 exe 承载参数，避免踩到「shell 内置命令不是 exe」的坑：
        //   - Windows 的 echo 是 cmd 内置命令，没有 echo.exe，direct 模式调不到；
        //   - POSIX 的 /bin/echo 是真实 exe，可以直接用。
        // findstr 会把「包含该竖线的行」原样打印出来，因此只要看到字面 a|b，
        // 就说明竖线是作为单个 argv 元素被解释器之外的进程收到的。
        Path probe = Files.createTempFile("tiangong-argprobe", ".txt");
        try {
            Files.writeString(probe, "a|b" + System.lineSeparator(),
                    StandardCharsets.UTF_8);

            CommandLine cmd = CommandLine.isWindows()
                    ? CommandLine.direct(List.of("findstr.exe", "a|b", probe.toString()))
                    : CommandLine.direct(List.of("/bin/grep", "a|b", probe.toString()));

            ExecResult r = sandbox.exec(cmd, context, ExecOptions.builder()
                    .timeout(Duration.ofSeconds(10)).build());

            assertEquals(0, r.exitCode().orElse(-1),
                    "退出码应为 0，实际输出: [" + r.output() + "]");
            assertTrue(r.output().contains("a|b"),
                    "竖线应作为字面参数被匹配并回显（证明未经 shell 解释），实际: [" + r.output() + "]");
        } finally {
            Files.deleteIfExists(probe);
        }
    }

    @Test
    @DisplayName("shell 模式：同一个竖线被解释成管道（与 direct 形成对照）")
    void shellModeDoesInterpretShellSyntax() {
        // 对照组：如果没有这条，上一条「未经 shell 解释」就只是断言了一个巧合。
        // 这里竖线两侧都是命令，shell 会执行成管道：echo a 的输出进 findstr a。
        CommandLine cmd = CommandLine.isWindows()
                ? CommandLine.shell("echo a|findstr a")
                : CommandLine.shell("echo a|grep a");

        ExecResult r = sandbox.exec(cmd, context, ExecOptions.builder()
                .timeout(Duration.ofSeconds(10)).build());

        assertEquals(0, r.exitCode().orElse(-1),
                "管道应正常执行，实际输出: [" + r.output() + "]");
        // 若竖线是字面量，输出会变成 "a|findstr a" 这一整行；
        // 被解释成管道后，输出只剩 "a"。
        assertFalse(r.output().contains("|"),
                "竖线应被 shell 消费掉（不残留在输出中），实际: [" + r.output() + "]");
        assertTrue(r.output().contains("a"),
                "管道右侧命令应有输出，实际: [" + r.output() + "]");
    }

    @Test
    @DisplayName("默认超时与输出上限有合理兜底值")
    void defaultsAreSane() {
        assertEquals(Duration.ofSeconds(30), sandbox.defaultTimeout());
        assertEquals(1024 * 1024, sandbox.defaultMaxOutputBytes());
        assertEquals("local", sandbox.name());
        // close 幂等
        assertDoesNotThrow(() -> {
            sandbox.close();
            sandbox.close();
        });
    }

    @Test
    @DisplayName("CommandLine 工厂拒绝空输入")
    void commandLineRejectsEmpty() {
        assertThrows(RuntimeException.class, () -> CommandLine.direct(List.of()));
        assertThrows(RuntimeException.class, () -> CommandLine.shell("  "));
        assertThrows(RuntimeException.class, () -> CommandLine.shell(null));
    }

    @Test
    @DisplayName("沙箱接口默认值可直接用（不覆写也不为 null）")
    void interfaceDefaultsApply() {
        Sandbox minimal = new Sandbox() {
            @Override
            public String name() {
                return "minimal";
            }

            @Override
            public ExecResult exec(CommandLine cmd, AgentContext ctx) {
                return ExecResult.of("minimal", "", 0, false, 0, 0);
            }

            @Override
            public ExecResult exec(CommandLine cmd, AgentContext ctx,
                                   ExecOptions options) {
                return exec(cmd, ctx);
            }
        };
        assertEquals(Duration.ofSeconds(30), minimal.defaultTimeout());
        assertEquals(1024 * 1024, minimal.defaultMaxOutputBytes());
        assertEquals(StandardCharsets.UTF_8, minimal.defaultCharset());
        assertDoesNotThrow(minimal::close);
    }
}
