package org.wutong.tiangong.skill.runtime;

import lombok.extern.slf4j.Slf4j;
import org.wutong.tiangong.annotations.annotation.framework.Component;
import org.wutong.tiangong.annotations.annotation.framework.inject.Inject;
import org.wutong.tiangong.common.agent.context.AgentContext;
import org.wutong.tiangong.common.sandbox.CommandLine;
import org.wutong.tiangong.common.sandbox.ExecOptions;
import org.wutong.tiangong.common.sandbox.ExecResult;
import org.wutong.tiangong.common.sandbox.Sandbox;
import org.wutong.tiangong.core.sandbox.SandboxRegistry;
import org.wutong.tiangong.core.script.ScriptRuntime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 系统 shell 脚本运行时：{@code .sh} / {@code .bat} / {@code .cmd}，
 * 以及探测到解释器时的 {@code .py}。<br>
 * <br>
 * <h2>为什么 py 要探测</h2>
 * 系统有没有 python 是不确定的。接管一个自己跑不了的后缀，后果比不接管糟得多：
 * 注册表认为有人负责 → 模型认为脚本已执行 → 实际什么都没发生。
 * 所以 {@link #supportedExtensions()} 是<b>动态计算</b>的 —— 这正是
 * {@code ScriptRuntime} 用接口而非注解的理由之一（注解只能挂常量）。
 *
 * <h2>执行一律走沙箱</h2>
 * 技能脚本来自第三方，必须受超时、输出限流与取消的约束，不能直接
 * {@code new ProcessBuilder}。沙箱实例取自注册表（不能反射 new，
 * 否则其 {@code @Inject} 字段会静默为 null）。
 */
@Slf4j
@Component
public class ShellScriptRuntime implements ScriptRuntime {

    static final String NAME = "shell";

    @Inject
    SandboxRegistry sandboxes;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> supportedExtensions() {
        List<String> exts = new ArrayList<>();
        if (isWindows()) {
            exts.add("bat");
            exts.add("cmd");
        } else {
            exts.add("sh");
            exts.add("bash");
        }
        // 解释器存在才接管：诚实地说不，好过执行时才炸
        if (ExecutableLocator.find("python").isPresent()
                || ExecutableLocator.find("python3").isPresent()) {
            exts.add("py");
        }
        return exts;
    }

    @Override
    public ExecResult run(Path script, List<String> args, AgentContext context, ExecOptions options) {
        List<String> argv = new ArrayList<>();
        String ext = extensionOf(script);
        if ("py".equals(ext)) {
            Optional<Path> python = ExecutableLocator.find("python3")
                    .or(() -> ExecutableLocator.find("python"));
            if (python.isEmpty()) {
                return ExecResult.failed(NAME, "未找到 python 解释器", 0);
            }
            argv.add(python.get().toString());
        }
        argv.add(script.toAbsolutePath().toString());
        if (args != null) {
            argv.addAll(args);
        }
        Sandbox sandbox = sandboxes == null ? null : sandboxes.defaultSandbox();
        if (sandbox == null) {
            return ExecResult.failed(NAME, "沙箱不可用：脚本拒绝裸跑", 0);
        }
        log.debug("执行脚本 [{}] 参数 {}", script, args);
        return sandbox.exec(CommandLine.direct(argv), context,
                options == null ? ExecOptions.none() : options);
    }

    private static String extensionOf(Path script) {
        String name = script.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
