package com.taixu.tiangong.common.sandbox;

import com.taixu.tiangong.common.agent.context.AgentContext;

import java.time.Duration;

/**
 * 沙箱：把「命令在什么环境里执行」作为框架扩展点。<br>
 * <br>
 * <h2>命名说明 —— 这是个刻意的选择</h2>
 * 本接口叫 {@code Sandbox} 但<b>不承诺隔离强度</b>。默认实现
 * {@link LocalSandbox} 直接在宿主机当前进程的用户身份下执行，<b>没有任何隔离</b>。
 * 「沙箱」在此处指的是<b>「命令的统一出口」</b>，而非「安全边界」。
 * 真正的隔离由具体实现的 {@code Sandbox} 决定：<br>
 * POSIX 可用 namespace / cgroup，Windows 可用 Job Object，或直接进容器（Docker / WSL）。<br>
 * 框架不替用户选择强度，也不替用户承担「出了逃逸算谁的」这类责任。
 *
 * <h2>实现必须遵守的契约</h2>
 * <ul>
 *     <li><b>协作式取消</b>：等待进程期间须周期性检查
 *         {@code context.isCancelled()}（建议 ≤ 200ms 轮询粒度，对齐
 *         {@code EntranceChannel#await} 的约定），为真时杀灭进程并返回
 *         {@code ExecResult.cancelled}。直接在 {@code waitFor()} 上阻塞会让
 *         取消与关机信号打不进来</li>
 *     <li><b>超时必终止</b>：到达超时必须杀灭进程<b>及其子进程树</b>，返回
 *         {@code ExecResult.timedOut}。只杀顶层会留下孤儿进程</li>
 *     <li><b>输出必限流</b>：产出超过上限即截断并置 {@code truncated = true}。
 *         失控命令可在数秒内产出 GB 级输出，撑爆堆或 LLM 上下文</li>
 *     <li><b>必须 drain 流</b>：同时读 stdout 与 stderr（建议各起一个读取动作，
 *         或用 {@code redirectErrorStream(true)}）。只等进程退出而缓冲区先满，
 *         会造成<b>死锁</b></li>
 *     <li><b>不写检查点</b>：本接口不负责检查点写入；由执行器在调用
 *         {@code exec} 前后统一处理（不变量 A6 / A8）</li>
 *     <li><b>生命周期</b>：{@link #close()} 在框架关闭时调一次（shutdown hook），
 *         实现应在此释放资源（清理临时镜像、停止容器、关闭连接池）。
 *         幂等：多次调用不抛异常</li>
 * </ul>
 *
 * <h2>超时与身份都可由外部决定</h2>
 * 本接口<b>不自己解析超时来源</b>。框架（{@code AgentNodeDefinition#invokeCommand}）
 * 负责合并「全局配置 → 注解 → 单次参数」这条优先级链，把最终值放进
 * {@link ExecOptions} 传进来。实现方只需「按给我的值执行」，
 * 不需要重复那套合并逻辑 —— 否则每个 {@code Sandbox} 实现都要写一遍，且容易写错。
 *
 * <h2>{@code @Sandbox} 注解的语义</h2>
 * 委托型：方法体仍会执行，框架把 {@code Sandbox} 实例作为方法参数注入进来，
 * 由<b>用户方法体决定何时、以及是否调用它</b>。这让用户能在执行前做危险判定
 * （就此返回拒绝、换更严格的沙箱、要求审批），而检查点 / 取消 / 异常兜底
 * 仍由框架保障。
 *
 * @see LocalSandbox 默认实现：本机直接执行，无隔离
 */
public interface Sandbox {

    /**
     * 沙箱唯一标识。全局配置 {@code sandbox.<name>.timeout} 按此名匹配，
     * 也用于执行日志与审计追溯。
     */
    String name();

    /**
     * 执行命令（采用合并后的默认参数）。<br>
     * <br>
     * 典型用法：{@code ExecResult r = box.exec(cmd, ctx);}
     * —— 用户不必关心超时从哪来，框架已按优先级链算好。
     *
     * @param cmd     待执行命令
     * @param context 工作流上下文，用于响应 {@code isCancelled()}
     * @return 执行结果；超时 / 取消<b>不抛异常</b>，而是体现在结果字段中
     * @throws SandboxException 沙箱自身故障（容器不可用、镜像拉取失败、二进制不存在）
     */
    ExecResult exec(CommandLine cmd, AgentContext context);

    /**
     * 执行命令（本次显式给出运行期参数）。<br>
     * <br>
     * 用于「同一节点下某次调用需要不同参数」的场景
     * —— 例如 {@code git status} 用默认 5s，而 {@code git clone} 显式放宽到 5 分钟。
     *
     * @param cmd     待执行命令
     * @param context 工作流上下文
     * @param options 本次的运行期覆盖项；{@code null} 等价于 {@link #exec(CommandLine, AgentContext)}
     * @return 执行结果
     * @throws SandboxException 沙箱自身故障
     */
    ExecResult exec(CommandLine cmd, AgentContext context, ExecOptions options);

    /**
     * 本沙箱的默认超时 —— 优先级链的第 1 层（最低）。<br>
     * <br>
     * 接口已给出 {@code 30s} 的兜底值，实现可覆写。
     * <b>不得返回 {@code null} 或负值</b>：框架的兜底逻辑会在拿到非法值时回退到 30s，
     * 但那属于「实现写错了被兜住」，不应作为正常路径。
     */
    default Duration defaultTimeout() {
        return Duration.ofSeconds(30);
    }

    /**
     * 本沙箱的默认输出上限（字节）—— 优先级链的第 1 层（最低）。<br>
     * 默认 1 MiB。实现可按环境调整，但<b>不建议取消限制</b>。
     */
    default int defaultMaxOutputBytes() {
        return 1024 * 1024;
    }

    /**
     * 输出解码字符集。<br>
     * Windows 控制台默认非 UTF-8，中文输出会乱码；实现应给出合理默认
     * （{@link LocalSandbox} 默认 UTF-8，可在 {@code ExecOptions} 里覆盖）。
     */
    default java.nio.charset.Charset defaultCharset() {
        return java.nio.charset.StandardCharsets.UTF_8;
    }

    /**
     * 框架关闭时调用一次（shutdown hook）。<br>
     * 实现须在此停止接受新任务并释放资源。<b>必须幂等</b>：多次调用不应抛异常。<br>
     * 默认空操作，对齐 {@code EntranceChannel#close()} 的约定。
     */
    default void close() {
        // 默认无资源需释放
    }
}
