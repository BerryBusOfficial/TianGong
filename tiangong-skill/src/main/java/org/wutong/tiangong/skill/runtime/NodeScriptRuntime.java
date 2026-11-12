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
 * TypeScript / JavaScript 脚本运行时：{@code .ts} / {@code .tsx} / {@code .js} / {@code .mjs}。<br>
 * <br>
 * <h2>为什么这是生态里最难适配的一层</h2>
 * TypeScript <b>不能直接执行</b>，必须先 transpile（tsx / esbuild / ts-node），
 * 这必然要求宿主装有 Node 工具链。而且这些脚本还会用 Node 标准库（{@code fs}、
 * {@code child_process}），GraalVM 那类纯 ECMAScript 引擎覆盖不了 —— 它只有语言，没有 API。<br>
 * <br>
 * 因此本类的立场是：<b>能用就用，不能用就明确退出</b>。
 * 框架不内置 Node，也不做 TS→Java 的 AOT 翻译（成本高、保真度差、脚本一更新就得重来）。
 *
 * <h2>命令构造：direct 而非 shell</h2>
 * 走 {@link CommandLine#direct} 并传入探测到的绝对路径，脚本路径作为参数原样传递，
 * <b>不经 shell 拼接</b> —— 脚本来自第三方，路径里带空格或元字符都不应被解释。
 */
@Slf4j
@Component
public class NodeScriptRuntime implements ScriptRuntime {

    static final String NAME = "node";

    /** TypeScript 执行器：tsx 能直接跑 .ts，无需预编译步骤 */
    private static final String TS_RUNNER = "tsx";

    @Inject
    SandboxRegistry sandboxes;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> supportedExtensions() {
        // 没有 node 就一个后缀都不接管
        return ExecutableLocator.find("npx").isPresent()
                ? List.of("ts", "tsx", "js", "mjs", "jsx")
                : List.of();
    }

    @Override
    public ExecResult run(Path script, List<String> args, AgentContext context, ExecOptions options) {
        Optional<Path> npx = ExecutableLocator.find("npx");
        if (npx.isEmpty()) {
            return ExecResult.failed(NAME, "未检测到 npx：TypeScript 脚本需要 Node 工具链", 0);
        }
        Sandbox sandbox = sandboxes == null ? null : sandboxes.defaultSandbox();
        if (sandbox == null) {
            return ExecResult.failed(NAME, "沙箱不可用：脚本拒绝裸跑", 0);
        }
        List<String> argv = new ArrayList<>();
        argv.add(npx.get().toString());
        if (isTypeScript(script)) {
            argv.add(TS_RUNNER);
        }
        argv.add(script.toAbsolutePath().toString());
        if (args != null) {
            argv.addAll(args);
        }
        log.debug("执行脚本 [{}]（npx {}）", script, isTypeScript(script) ? TS_RUNNER : "");
        return sandbox.exec(CommandLine.direct(argv), context,
                options == null ? ExecOptions.none() : options);
    }

    private static boolean isTypeScript(Path script) {
        String name = script.getFileName().toString().toLowerCase();
        return name.endsWith(".ts") || name.endsWith(".tsx");
    }
}
