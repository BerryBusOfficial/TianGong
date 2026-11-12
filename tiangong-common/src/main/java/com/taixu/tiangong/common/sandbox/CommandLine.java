package com.taixu.tiangong.common.sandbox;

import com.taixu.tiangong.common.exception.SystemRuntimeException;

import java.util.List;

/**
 * 一条待执行的命令 —— <b>容器而非解析器</b>。<br>
 * <br>
 * <h2>为什么不做「智能切分」</h2>
 * 一条命令有两种执行模式，切分规则完全不同，且不可互推：
 * <ul>
 *     <li><b>直接执行（direct）</b>：需要已切分好的 argv。此时引号要按 shell 规则剥掉 ——
 *         {@code ["ls", "-la", "C:\\Program Files"]}</li>
 *     <li><b>交给 shell（shell）</b>：<b>不能切分</b>，整行原样传给 {@code /bin/sh -c} 或
 *         {@code cmd /c}。管道 {@code |}、短路 {@code &&}、重定向 {@code >} 都是 shell 语法，
 *         切分即毁 —— {@code ["echo","a","|","grep","a"]} 会被当成 {@code echo} 的字面参数</li>
 * </ul>
 * 更关键的是 Windows {@code cmd /c} 的引号规则自成一套（与 POSIX shell 不同），
 * {@code ProcessBuilder} 在 Windows 上的引号转义历史上存在多个 JDK 缺陷。
 * 「给我一个字符串我帮你猜」这条路，在本框架的目标平台（桌面 Windows）上<b>必然出错</b>。<br>
 * <br>
 * 因此本类不提供自动切分，只提供两个<b>语义显式</b>的静态工厂：调用方必须声明
 * 自己要哪一种，框架不做猜测。
 *
 * <h2>推荐做法：让 LLM 直接输出 argv</h2>
 * 工具的 JSON schema 声明 {@code command: {type: array, items: {type: string}}}，
 * LLM 直接产出分段好的数组，框架零解析。这是<b>唯一不需要碰引号规则</b>的路径。
 *
 * <h2>不变量</h2>
 * <ul>
 *     <li>{@link #argv()} 永不为 {@code null}；direct 模式下至少含一个元素（程序名）</li>
 *     <li>{@link #isShell()} 为 {@code true} 时 argv 为空列表，
 *         执行方应当只用 {@link #raw()} 传给 shell</li>
 *     <li>{@link #display()} / {@link #raw()} 仅用于日志 / 审计 / 审批展示，
 *         <b>不参与执行，不可再被解析回 argv</b></li>
 *     <li>本类<b>不可变</b>，可安全跨线程传递</li>
 * </ul>
 */
public final class CommandLine {

    /** POSIX shell 程序名（{@code -c} 后接脚本原文） */
    public static final List<String> POSIX_SHELL = List.of("/bin/sh", "-c");

    /** Windows shell 程序名（{@code /d} 关闭 AutoRun，减少注册表脚本的注入面） */
    public static final List<String> WINDOWS_SHELL = List.of("cmd", "/d", "/c");

    /** 已切分好的参数（argv[0] = 程序名）；shell 模式下为空列表 */
    private final List<String> argv;

    /** shell 模式下的脚本原文；direct 模式下 {@code null} */
    private final String raw;

    /** 是否交给 shell 解释执行 */
    private final boolean shell;

    private CommandLine(List<String> argv, String raw, boolean shell) {
        this.argv = argv;
        this.raw = raw;
        this.shell = shell;
    }

    /**
     * 直接执行模式：argv 已切分完毕，不经 shell。<br>
     * <br>
     * <b>推荐模式</b>：无引号歧义、无 shell 注入面（参数不会被二次解释）。
     *
     * @param argv 程序名 + 参数，不可为空；argv[0] 为程序名
     * @throws SystemRuntimeException argv 为 null 或空
     */
    public static CommandLine direct(List<String> argv) {
        if (argv == null || argv.isEmpty()) {
            throw new SystemRuntimeException("CommandLine.direct 的 argv 不可为空");
        }
        return new CommandLine(List.copyOf(argv), null, false);
    }

    /**
     * 直接执行模式的便捷重载。
     *
     * @param argv 程序名 + 参数
     */
    public static CommandLine direct(String... argv) {
        return direct(List.of(argv));
    }

    /**
     * shell 模式：整行原样交给 {@code /bin/sh -c}（POSIX）或 {@code cmd /d /c}（Windows）。<br>
     * <br>
     * 支持管道 / 短路 / 重定向 —— 但同时意味着<b>命令内容会被 shell 二次解释</b>，
     * 这是注入面。仅在确需 shell 语法时使用。
     *
     * @param scriptLine 脚本原文，不可为空白
     * @throws SystemRuntimeException scriptLine 为 null 或空白
     */
    public static CommandLine shell(String scriptLine) {
        if (scriptLine == null || scriptLine.isBlank()) {
            throw new SystemRuntimeException("CommandLine.shell 的脚本内容不可为空");
        }
        return new CommandLine(List.of(), scriptLine, true);
    }

    /**
     * 已切分好的参数（direct 模式）。<br>
     * shell 模式下返回<b>空列表</b> —— 此时请用 {@link #raw()}。
     */
    public List<String> argv() {
        return argv;
    }

    /**
     * 原始命令行（shell 模式 = 脚本原文；direct 模式 = {@code null}）。<br>
     * <b>仅用于日志 / 审计 / 审批展示，不参与执行。</b>
     */
    public String raw() {
        return raw;
    }

    /** 是否交给 shell 解释执行 */
    public boolean isShell() {
        return shell;
    }

    /**
     * 用于展示 / 审批 / 审计的命令文本。<br>
     * shell 模式返回脚本原文；direct 模式用空格拼接 argv
     * （<b>仅展示</b>，不可再拿去解析回 argv）。
     */
    public String display() {
        return shell ? raw : String.join(" ", argv);
    }

    /**
     * 当前系统应选用的 shell 程序。<br>
     * Windows 返回 {@code cmd /d /c}，其它返回 {@code /bin/sh -c}。
     */
    public static List<String> defaultShell() {
        return isWindows() ? WINDOWS_SHELL : POSIX_SHELL;
    }

    /** 当前系统是否为 Windows（供沙箱实现判定进程管理命令） */
    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    @Override
    public String toString() {
        return "CommandLine{" + display() + "}";
    }
}
