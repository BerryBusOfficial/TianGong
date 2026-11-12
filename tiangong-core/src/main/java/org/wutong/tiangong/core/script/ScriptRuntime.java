package org.wutong.tiangong.core.script;

import org.wutong.tiangong.common.agent.context.AgentContext;
import org.wutong.tiangong.common.sandbox.ExecOptions;
import org.wutong.tiangong.common.sandbox.ExecResult;

import java.nio.file.Path;
import java.util.List;

/**
 * 脚本运行时：把「某个后缀的脚本」翻译成一次可执行命令。<br>
 * <br>
 * <h2>为什么需要这一层</h2>
 * 生态里的技能包常在 {@code scripts/} 下带脚本：{@code .sh} / {@code .py} / {@code .ts}。
 * 它们的共同点是<b>需要外部工具链</b> —— shell、python、node。框架<b>不可能内置</b>这些运行时，
 * 也不该替应用决定装不装。因此这里只定义接入口，实现交给适配层（{@code tiangong-skill}）
 * 或应用自己。<br>
 * <br>
 * 与 {@code CommandLineResolver} 是同一类扩展点：框架给不出默认实现，
 * 装配走「<b>优先取容器 Bean，取不到才没有</b>」的既有规则。
 *
 * <h2>契约</h2>
 * <ul>
 *     <li>{@link #supports} 必须能<b>诚实地说不</b>：环境里没有对应工具链时返回
 *         {@code false}，而不是注册进来再在执行时炸。判定时机由实现决定（可缓存探测结果）</li>
 *     <li>执行失败<b>不抛异常</b>，用 {@link ExecResult#failed} 表达 ——
 *         脚本跑挂是正常业务结果，与「沙箱 / 超时 / 取消不抛异常」同一立场</li>
 *     <li>真正的进程一律经过<b>沙箱</b>，不裸跑：技能脚本来自第三方，必须受超时、
 *         输出限流与取消的约束</li>
 * </ul>
 */
public interface ScriptRuntime {

    /**
     * 本运行时接管的后缀（<b>不含点</b>，如 {@code ["sh", "bash"]}）。<br>
     * 环境缺少工具链时应返回<b>空列表</b> —— 诚实地说不，好过注册进来再在执行时炸。<br>
     * 同一后缀被多个运行时声明时，<b>先注册者优先</b>。
     */
    List<String> supportedExtensions();

    /**
     * 运行脚本。
     *
     * @param script  脚本文件路径
     * @param args    传给脚本的参数
     * @param context 当前会话上下文（沙箱执行需要）
     * @param options 执行选项；{@link ExecOptions#none()} 表示全部用默认
     * @return 执行结果，失败也以结果表达
     */
    ExecResult run(Path script, List<String> args, AgentContext context, ExecOptions options);

    /** 运行时名字，用于日志与错误信息里区分是谁在执行 */
    String name();

    /** 是否接管某个后缀（便利方法，按 {@link #supportedExtensions()} 判断） */
    default boolean supports(String extension) {
        List<String> supported = supportedExtensions();
        if (supported == null || extension == null) {
            return false;
        }
        String ext = extension.startsWith(".") ? extension.substring(1) : extension;
        return supported.contains(ext.toLowerCase());
    }
}
