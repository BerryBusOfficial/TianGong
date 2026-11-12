package org.wutong.tiangong.core.sandbox;

import lombok.extern.slf4j.Slf4j;
import org.wutong.tiangong.common.agent.context.AgentContext;
import org.wutong.tiangong.common.sandbox.CommandLine;
import org.wutong.tiangong.common.sandbox.ExecOptions;
import org.wutong.tiangong.common.sandbox.ExecResult;
import org.wutong.tiangong.common.sandbox.Sandbox;
import org.wutong.tiangong.common.sandbox.SandboxException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 默认沙箱实现：在<b>宿主机当前进程的用户身份下直接执行</b>。<br>
 * <br>
 * <h2>不提供任何隔离 —— 这是刻意的</h2>
 * 名字叫沙箱，但本实现<b>不是安全边界</b>：命令拿到的权限与框架进程完全相同，
 * 可读写同目录、可访问同一网络、环境变量继承如故。
 * 「沙箱」在框架里的含义是<b>「命令的统一出口」</b>，而非「隔离强度」。<br>
 * 选它作为默认，是因为多数场景（桌面宠物跑个 {@code dir} / {@code git status}）
 * 进容器纯属负收益 —— 启动慢、跨平台难、Windows 上尤其麻烦。
 * 框架不替用户决定安全等级；需要真隔离请自行实现 {@link Sandbox} 并替换。<br>
 * <br>
 * <b>「不隔离」是默认，「无限制」不是默认。</b>本实现强制保障以下事项，
 * 因为它们一旦写错<b>很难被发现</b>：
 *
 * <h2>强制保障</h2>
 * <ul>
 *     <li><b>超时必杀进程树</b>：到达超时用平台手段杀灭<b>子进程及其全部后代</b>
 *         （Windows 用 {@code taskkill /T /F}，POSIX 依赖 {@code destroyForcibly}）。
 *         只杀顶层会留下孤儿进程继续占用资源</li>
 *     <li><b>输出必限流</b>：累积达上限即截断并置 {@code truncated = true}。
 *         失控命令（{@code find / -type f}）可在数秒内产出 GB 级输出，
 *         既撑爆堆，也直接超出 LLM 上下文窗口</li>
 *     <li><b>协作式取消</b>：等待期间周期性检查 {@code context.isCancelled()}，
 *         粒度与 {@code EntranceChannel#await} 约定一致（≤ {@link #POLL_MILLIS}）</li>
 *     <li><b>必须 drain 流</b>：合并 stderr 进 stdout，并在<b>独立线程</b>里持续读取。
 *         只在 {@code waitFor()} 上干等而缓冲区先满，会形成写阻塞 / 等阻塞的<b>死锁</b></li>
 * </ul>
 *
 * <h2>编码</h2>
 * 默认 UTF-8。Windows 控制台默认使用本地代码页（中文环境通常是 GBK），
 * 按 UTF-8 解码会乱码 —— 可在 {@link ExecOptions#charset(Charset)} 里显式指定。
 *
 * <h2>已知限制</h2>
 * <ul>
 *     <li>Windows 下 {@code taskkill /T /F} 依赖该命令可用、且目标进程未被提权保护；
 *         被保护的系统进程可能杀不掉（此时记 warn 后继续）</li>
 *     <li>不提供 stdin 输入：本实现的场景不需要交互式命令。需要时自行扩展</li>
 *     <li>Windows 的进程树杀灭路径按平台文档实现，<b>上线前应在目标机器做实机冒烟</b></li>
 * </ul>
 */
@Slf4j
public class LocalSandbox implements Sandbox {

    /** 沙箱名：对应的全局配置键为 {@code sandbox.local.timeout} */
    public static final String NAME = "local";

    /**
     * 取消信号检查粒度（毫秒）。对齐 {@code EntranceChannel#await} 的 200ms 约定 ——
     * 保证工作流取消 / 关机信号能在这个窗口内打动正在等待命令的线程
     */
    static final long POLL_MILLIS = 200L;

    /** 单块读取大小（字节） */
    private static final int CHUNK_SIZE = 8192;

    /** taskkill 的最大等待时长（秒） */
    private static final long KILLER_WAIT_SECONDS = 5L;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ExecResult exec(CommandLine cmd, AgentContext context) {
        return exec(cmd, context, null);
    }

    @Override
    public ExecResult exec(CommandLine cmd, AgentContext context, ExecOptions options) {
        ExecOptions opts = options == null ? ExecOptions.none() : options;
        Charset charset = opts.charset() != null ? opts.charset() : defaultCharset();
        int maxBytes = opts.maxOutputBytes() != null ? opts.maxOutputBytes() : defaultMaxOutputBytes();
        long timeoutMillis = opts.timeout() != null
                ? opts.timeout().toMillis()
                : defaultTimeout().toMillis();

        long startAt = System.currentTimeMillis();
        log.debug("LocalSandbox 执行命令: {} (timeout={}ms, maxBytes={})",
                cmd.display(), timeoutMillis, maxBytes);

        ProcessBuilder builder = new ProcessBuilder(buildProcessCommand(cmd));
        // 合并 stderr → stdout：单路读取即可避免「只抽干一路而另一路写满」的死锁
        builder.redirectErrorStream(true);
        applyEnvironment(builder, opts);
        if (opts.workDir() != null) {
            builder.directory(opts.workDir().toFile());
        }

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            // 二进制不存在 / 工作目录不可访问 —— 属沙箱（环境）故障，而非命令本身失败
            throw new SandboxException(name(), "无法启动进程 [" + cmd.display() + "]: " + e.getMessage(), e);
        }

        OutputDrainer drainer = new OutputDrainer(process.getInputStream(), charset, maxBytes);
        Thread drainThread = new Thread(drainer, "tiangong-sandbox-drain-" + process.pid());
        drainThread.setDaemon(true);
        drainThread.start();

        ExitReason reason = awaitTermination(process, context, timeoutMillis, drainer);

        String output = drainer.snapshot();
        long elapsed = System.currentTimeMillis() - startAt;
        long total = drainer.totalBytesRead();

        if (reason == ExitReason.TIMEOUT) {
            log.warn("命令超时（{}ms），已终止进程树: {}", timeoutMillis, cmd.display());
            return ExecResult.timedOut(name(), output, total, elapsed);
        }
        if (reason == ExitReason.CANCELLED) {
            log.info("命令被工作流取消，已终止进程树: {}", cmd.display());
            return ExecResult.cancelled(name(), output, total, elapsed);
        }

        int exitCode = process.exitValue();
        boolean truncated = drainer.isTruncated();
        log.debug("命令执行完成: exit={}, bytes={}, truncated={}, {}ms", exitCode, total, truncated, elapsed);
        return ExecResult.of(name(), output, exitCode, truncated, total, elapsed);
    }

    /**
     * 把 {@link CommandLine} 转成 {@link ProcessBuilder} 可执行的形式。<br>
     * <br>
     * direct 模式直接传 argv（不经 shell，无二次解释）；
     * shell 模式把脚本原文作为<b>单个参数</b>传给 {@code cmd /d /c} 或 {@code /bin/sh -c}
     * —— 关键是不能再把脚本按空格切开，那样管道 / 重定向会退化成字面参数。
     */
    private List<String> buildProcessCommand(CommandLine cmd) {
        if (!cmd.isShell()) {
            return cmd.argv();
        }
        List<String> shell = CommandLine.defaultShell();
        List<String> command = new ArrayList<>(shell.size() + 1);
        command.addAll(shell);
        command.add(cmd.raw());
        return command;
    }

    /**
     * 应用环境变量策略。<br>
     * <br>
     * 默认继承父进程环境并叠加 {@code opts.env()}；
     * 显式 {@code inheritEnv(false)} 时先清空再只给 {@code opts.env()}。
     */
    private void applyEnvironment(ProcessBuilder builder, ExecOptions opts) {
        if (!opts.inheritEnv()) {
            builder.environment().clear();
        }
        if (!opts.env().isEmpty()) {
            builder.environment().putAll(opts.env());
        }
    }

    /**
     * 等待进程结束，期间周期性检查超时与取消信号。<br>
     * <br>
     * 用带时限的 {@code waitFor} 分片轮询，而非一次性阻塞到底：分片让
     * {@code isCancelled()} 有机会在 {@link #POLL_MILLIS} 内生效。
     * 若一次性等到命令自然结束，取消信号要届时才能被看到 —— 那就太晚了。
     */
    private ExitReason awaitTermination(Process process, AgentContext context,
                                        long timeoutMillis, OutputDrainer drainer) {
        long deadline = System.currentTimeMillis() + timeoutMillis;

        while (true) {
            if (context != null && context.isCancelled()) {
                killProcessTree(process);
                return ExitReason.CANCELLED;
            }
            if (System.currentTimeMillis() >= deadline) {
                killProcessTree(process);
                return ExitReason.TIMEOUT;
            }
            // 输出已达上限：不必再等命令自然结束，提前终止（继续等只是白耗资源）
            if (drainer.isTruncated()) {
                killProcessTree(process);
                return ExitReason.COMPLETED_TRUNCATED;
            }
            try {
                if (process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                    return ExitReason.NORMAL;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                killProcessTree(process);
                return ExitReason.CANCELLED;
            }
        }
    }

    /**
     * 强制杀灭进程<b>及其子树</b>。<br>
     * <br>
     * Java 的 {@code Process#destroy} / {@code destroyForcibly} 在 Windows 上
     * <b>不保证</b>波及子进程；Java 9+ 的 {@code ProcessHandle#destroyAllForcibly()}
     * 语义更好但对已 detach 的孙进程仍不可靠。
     * 因此 Windows 上额外调用 {@code taskkill /pid <pid> /T /F}（{@code /T} = 含子树）。<br>
     * <br>
     * <b>待实机验证</b>：本分支依据平台文档实现，尚未在目标环境跑通过真实验证。
     * 启用强隔离保证前应先补冒烟测试（起一个会派生子进程的命令，超时后检查后代是否存活）。
     */
    private void killProcessTree(Process process) {
        long pid = process.pid();
        try {
            process.destroyForcibly();
        } catch (RuntimeException e) {
            log.warn("销毁进程 [{}] 失败（将由平台手段兜底）: {}", pid, e.getMessage());
        }

        if (!CommandLine.isWindows()) {
            return;
        }
        try {
            Process killer = new ProcessBuilder("taskkill", "/pid", String.valueOf(pid), "/T", "/F")
                    .redirectErrorStream(true)
                    .start();
            if (!killer.waitFor(KILLER_WAIT_SECONDS, TimeUnit.SECONDS)) {
                killer.destroyForcibly();
                log.warn("taskkill 在 {} 秒内未结束（目标进程 {} 可能仍存活）", KILLER_WAIT_SECONDS, pid);
            }
        } catch (IOException e) {
            log.warn("taskkill 不可用，进程 [{}] 的子进程可能残留: {}", pid, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("等待 taskkill 时被中断（目标进程 {} 可能仍存活）", pid);
        }
    }

    /** 进程终止原因 */
    private enum ExitReason {
        /** 自然结束 */
        NORMAL,
        /** 超时被杀 */
        TIMEOUT,
        /** 工作流被取消 */
        CANCELLED,
        /** 输出达上限而提前终止（按「已完成」处理，结果带 truncated 标记） */
        COMPLETED_TRUNCATED
    }

    /**
     * 输出排空器：持续读流以免子进程写阻塞（死锁），并按字节上限截断。<br>
     * <br>
     * 在<b>独立线程</b>中运行。若父线程等待进程退出却不抽干输出，
     * 管道缓冲区写满后子进程会阻塞在 write 上，双方互等 —— 这是进程管理的经典死锁。<br>
     * <br>
     * 截断后<b>仍继续读取但不拼进结果</b>：既止住了内存增长，又不堵塞子进程。
     */
    static final class OutputDrainer implements Runnable {

        private final InputStream source;
        private final Charset charset;
        private final int maxBytes;
        private final StringBuilder buf = new StringBuilder();
        private volatile long totalBytesRead = 0;
        private volatile boolean truncated = false;

        OutputDrainer(InputStream source, Charset charset, int maxBytes) {
            this.source = source;
            this.charset = charset;
            this.maxBytes = maxBytes;
        }

        @Override
        public void run() {
            byte[] chunk = new byte[CHUNK_SIZE];
            try (InputStream in = source) {
                while (true) {
                    int n = in.read(chunk);
                    if (n < 0) {
                        return;                 // EOF
                    }
                    totalBytesRead += n;
                    int remain = maxBytes - buf.length();
                    if (remain <= 0) {
                        // 已装满：只更新计数，不拼进结果
                        truncated = true;
                        continue;
                    }
                    int take = Math.min(n, remain);
                    buf.append(new String(chunk, 0, take, charset));
                    if (take < n) {
                        truncated = true;
                    }
                }
            } catch (IOException e) {
                // 进程被强杀时管道关闭属正常现象（"The pipe is being closed" 等），降级为 debug
                log.debug("沙箱输出读取中断（进程可能已被终止）: {}", e.getMessage());
            }
        }

        String snapshot() {
            return buf.toString();
        }

        long totalBytesRead() {
            return totalBytesRead;
        }

        boolean isTruncated() {
            return truncated;
        }
    }

    @Override
    public Duration defaultTimeout() {
        return Duration.ofSeconds(30);
    }

    @Override
    public int defaultMaxOutputBytes() {
        return 1024 * 1024;   // 1 MiB
    }

    @Override
    public void close() {
        // 无共享资源需释放：进程均为一次性
    }
}
