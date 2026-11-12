package org.wutong.tiangong.common.sandbox;

import org.wutong.tiangong.common.exception.SystemRuntimeException;

/**
 * 沙箱自身执行失败。<br>
 * <br>
 * <h2>什么情况抛这个</h2>
 * 只用于「沙箱环境不可用」这类<b>基础设施故障</b>：
 * 容器守护进程未启动、镜像拉取失败、二进制不存在、工作目录不可访问等。
 *
 * <h2>什么情况不抛这个</h2>
 * <ul>
 *     <li><b>命令执行了但失败</b>（退出码非 0）→ 不抛，返回
 *         {@code ExecResult.of(..., exitCode, ...)}，让 LLM 看到失败原因自行重试</li>
 *     <li><b>超时</b> → 不抛，返回 {@code ExecResult.timedOut(...)}</li>
 *     <li><b>被工作流取消</b> → 不抛，返回 {@code ExecResult.cancelled(...)}</li>
 * </ul>
 * 区分标准：<b>「命令本身的结果」是业务数据，「沙箱坏了」才是异常。</b>
 * 把前者当异常抛出，会让 LLM 丢失失败原因，也无法表达部分输出。
 */
public class SandboxException extends SystemRuntimeException {

    public SandboxException(String sandboxName, String message) {
        super("[%s] %s".formatted(sandboxName, message));
    }

    public SandboxException(String sandboxName, String message, Throwable cause) {
        super("[%s] %s".formatted(sandboxName, message), cause);
    }

    public SandboxException(String message, Throwable cause) {
        super(message, cause);
    }
}
