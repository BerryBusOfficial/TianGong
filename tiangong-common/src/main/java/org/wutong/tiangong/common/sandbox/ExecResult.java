package org.wutong.tiangong.common.sandbox;

/**
 * 命令执行结果。<br>
 * <br>
 * <h2>不变量</h2>
 * <ul>
 *     <li>{@link #output()} 永不为 {@code null}（异常时为 {@code ""}）</li>
 *     <li>本类<b>不可变</b>，可安全跨线程传递、可安全写入检查点</li>
 *     <li>输出<b>必定已被截断处理</b>：沙箱实现保证 {@link #output()} 的长度
 *         不超过约定的上限，超出时 {@link #truncated()} 必须为 {@code true}</li>
 * </ul>
 *
 * <h2>为什么截断必须是实现方的责任</h2>
 * 一条失控命令（如 {@code find / -type f}）可以在数秒内产出 GB 级输出。
 * 若不限流：① 撑爆 JVM 堆；② 即使撑不爆，回灌 LLM 时也会超出上下文窗口，
 * 造成整轮对话失败。因此「截断」不是可选优化，是<b>契约义务</b>。
 *
 * <h2>三种终态</h2>
 * <ul>
 *     <li><b>正常结束</b>：{@code exitCode} 有值，{@code timedOut} / {@code cancelled} 为 false</li>
 *     <li><b>超时</b>：{@code timedOut = true}，进程已被杀灭</li>
 *     <li><b>被取消</b>：{@code cancelled = true}，响应了 {@code AgentContext#isCancelled()}</li>
 * </ul>
 * 注意超时与取消<b>不抛异常</b> —— 它们是正常的业务结果，应回灌给 LLM 让它自行判断。
 * 只有「沙箱自身故障」（容器不可用、镜像拉取失败）才抛异常。
 */
public final class ExecResult {

    /** 标准输出与标准错误合并后的内容（已截断） */
    private final String output;

    /** 进程退出码；超时/取消时为 {@code null} */
    private final Integer exitCode;

    /** 是否因超时而被终止 */
    private final boolean timedOut;

    /** 是否因工作流取消而被终止 */
    private final boolean cancelled;

    /** 输出是否被截断 */
    private final boolean truncated;

    /** 截断前的完整输出字节数（未被截断时等于 output 的字节数） */
    private final long totalBytes;

    /** 实耗毫秒 */
    private final long elapsedMillis;

    /** 执行所用的沙箱名，便于审计追溯 */
    private final String sandboxName;

    private ExecResult(String output, Integer exitCode, boolean timedOut,
                       boolean cancelled, boolean truncated, long totalBytes,
                       long elapsedMillis, String sandboxName) {
        this.output = output == null ? "" : output;
        this.exitCode = exitCode;
        this.timedOut = timedOut;
        this.cancelled = cancelled;
        this.truncated = truncated;
        this.totalBytes = totalBytes;
        this.elapsedMillis = elapsedMillis;
        this.sandboxName = sandboxName;
    }

    /** 正常结束 */
    public static ExecResult of(String sandboxName, String output, int exitCode,
                                boolean truncated, long totalBytes, long elapsedMillis) {
        return new ExecResult(output, exitCode, false, false,
                truncated, totalBytes, elapsedMillis, sandboxName);
    }

    /** 超时终止 */
    public static ExecResult timedOut(String sandboxName, String partialOutput,
                                      long totalBytes, long elapsedMillis) {
        return new ExecResult(partialOutput, null, true, false,
                true, totalBytes, elapsedMillis, sandboxName);
    }

    /** 被工作流取消 */
    public static ExecResult cancelled(String sandboxName, String partialOutput,
                                       long totalBytes, long elapsedMillis) {
        return new ExecResult(partialOutput, null, false, true,
                true, totalBytes, elapsedMillis, sandboxName);
    }

    /** 沙箱自身故障的统一表示（调用方也可选择抛异常，此为兜底） */
    public static ExecResult failed(String sandboxName, String message, long elapsedMillis) {
        return new ExecResult(message, null, false, false,
                false, message.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                elapsedMillis, sandboxName);
    }

    /** 已截断的输出内容（stdout + stderr 合并）。永不为 {@code null} */
    public String output() {
        return output;
    }

    /** 进程退出码；超时 / 取消 / 故障时无值 */
    public java.util.Optional<Integer> exitCode() {
        return java.util.Optional.ofNullable(exitCode);
    }

    /** 是否发生过超时 */
    public boolean timedOut() {
        return timedOut;
    }

    /** 是否因工作流取消而终止 */
    public boolean cancelled() {
        return cancelled;
    }

    /** 输出是否被截断。为 {@code true} 时应告知 LLM「输出不完整」 */
    public boolean truncated() {
        return truncated;
    }

    /** 截断前的完整字节数 */
    public long totalBytes() {
        return totalBytes;
    }

    /** 实耗毫秒 */
    public long elapsedMillis() {
        return elapsedMillis;
    }

    /** 执行此命令的沙箱名 */
    public String sandboxName() {
        return sandboxName;
    }

    /** 是否成功（退出码为 0 且未超时/取消） */
    public boolean success() {
        return !timedOut && !cancelled && exitCode != null && exitCode == 0;
    }

    @Override
    public String toString() {
        return "ExecResult{sandbox=%s, exit=%s, timedOut=%s, cancelled=%s, truncated=%s, bytes=%d, ms=%d}"
                .formatted(sandboxName, exitCode, timedOut, cancelled, truncated, totalBytes, elapsedMillis);
    }
}
