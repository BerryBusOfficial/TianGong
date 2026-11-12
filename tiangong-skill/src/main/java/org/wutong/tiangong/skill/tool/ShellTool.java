package org.wutong.tiangong.skill.tool;

import lombok.extern.slf4j.Slf4j;
import org.wutong.tiangong.annotations.annotation.framework.agent.AgentFunction;
import org.wutong.tiangong.annotations.annotation.framework.inject.Inject;
import org.wutong.tiangong.common.sandbox.CommandLine;
import org.wutong.tiangong.common.sandbox.ExecOptions;
import org.wutong.tiangong.common.sandbox.ExecResult;
import org.wutong.tiangong.common.sandbox.Sandbox;
import org.wutong.tiangong.core.sandbox.SandboxRegistry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 宿主工具的框架实现：生态技能正文里写的 {@code Bash} / {@code Read} 在这里落地。<br>
 * <br>
 * <h2>为什么按生态名注册</h2>
 * 技能正文写的是「用 Bash 跑这条命令」。若框架把同一个能力注册成
 * {@code shell_exec}，模型就会去找一个不存在的 {@code Bash} —— 技能看着能用，
 * 实际一步都走不动。直接以生态名注册，正文一个字都不用改。<br>
 * 宿主自己的命名（如 {@code sys_read}）由 {@link HostToolMapping} 兜底映射。<br>
 * <br>
 *
 * <h2>为什么不是 {@code @Component} 自动装配</h2>
 * {@code @AgentTool} 是元注解 {@code @Component}，一旦标上，classpath 里只要有本模块
 * 就会被无条件装配 —— 那意味着任何应用不声明 {@code @EnableSkill} 也会白得一个
 * <b>任意命令执行</b>工具。这是安全边界问题，不是便利性问题。<br>
 * 因此本类<b>不带</b> {@code @Component} / {@code @AgentTool}（此处仅保留注解 import 供
 * 文档引用），由 {@code SkillAutoConfiguration} 在开关打开时显式
 * {@code registerBean}。<br>
 * <br>
 *
 * <h2>安全边界</h2>
 * <ul>
 *     <li>命令一律走<b>沙箱</b>：超时杀进程树、输出限流、协作式取消都在沙箱里实现，
 *         不在这里重复造</li>
 *     <li>读文件限制单文件 {@value #MAX_READ_BYTES} 字节，且拒绝目录</li>
 *     <li><b>不提供 Write</b>：写文件会改工作区，策略必须由应用决定，
 *         框架替应用开口子是不负责任的。缺失的工具由
 *         {@link HostToolMapping#audit} 报出来，让应用自己补</li>
 * </ul>
 */
@Slf4j
public class ShellTool {

    /** 单文件读取上限：技能只需要读到内容，不需要把整个仓库塞进上下文 */
    public static final int MAX_READ_BYTES = 64 * 1024;

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    @Inject
    SandboxRegistry sandboxes;

    /**
     * 执行 shell 命令。<br>
     * 不经沙箱的执行一律拒绝 —— 技能脚本来自第三方。
     */
    @AgentFunction(
            name = "Bash",
            description = "在系统 shell 中执行命令并返回输出与退出码。"
                    + "用于构建、测试、查看目录等只读或幂等操作；不要用于交互式命令。"
    )
    public String bash(
            @AgentFunction.ToolParam(name = "command", description = "要执行的命令，如 mvn -q test") String command
    ) {
        if (command == null || command.isBlank()) {
            return "命令为空";
        }
        Sandbox sandbox = sandboxes == null ? null : sandboxes.defaultSandbox();
        if (sandbox == null) {
            return "沙箱不可用，命令拒绝执行";
        }
        log.info("技能工具 Bash 执行: {}", command);
        ExecResult result = sandbox.exec(
                CommandLine.shell(command),
                null,      // 工具拿不到会话上下文：沙箱对 null 有保护，代价是无法响应协作式取消
                ExecOptions.builder().timeout(DEFAULT_TIMEOUT).build());
        if (result.timedOut()) {
            return "命令超时（" + DEFAULT_TIMEOUT.toSeconds() + "s），部分输出：\n" + result.output();
        }
        return "退出码 " + result.exitCode().orElse(-1) + "\n" + result.output();
    }

    /**
     * 读取文件内容。
     */
    @AgentFunction(
            name = "Read",
            description = "读取文件文本内容。返回最多 " + MAX_READ_BYTES / 1024 + " KB；目录不可读。"
    )
    public String read(
            @AgentFunction.ToolParam(name = "path", description = "文件路径，相对路径按当前工作目录解析") String path
    ) {
        try {
            Path file = Path.of(path);
            if (Files.isDirectory(file)) {
                return "这是目录，不是文件: " + path;
            }
            if (!Files.isRegularFile(file)) {
                return "文件不存在: " + path;
            }
            long size = Files.size(file);
            if (size > MAX_READ_BYTES) {
                return "文件过大（" + size + " 字节，上限 " + MAX_READ_BYTES + "）";
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (InvalidPathException e) {
            return "路径非法: " + path;
        } catch (Exception e) {
            return "读取失败: " + e.getMessage();
        }
    }
}
