package com.taixu.tiangong.annotations.annotation.framework.agent.loop.command;

import com.taixu.tiangong.annotations.annotation.framework.meta.Agent;

import java.lang.annotation.*;

/**
 * 声明该命令节点<b>默认在哪个沙箱中执行</b>。<br>
 * <br>
 * <h2>委托型语义（务必读清楚）</h2>
 * 本注解<b>不会让方法体失效</b>。方法体照常执行，框架把 {@code Sandbox} 实例
 * 作为<b>方法参数</b>注入进来，由用户方法体决定「是否执行、何时执行、用什么执行」。<br>
 * <br>
 * 这样设计的理由：若注解一标就跳过方法体（所谓路由型），用户便无处安放
 * 「危险命令要拦截」这类逻辑。委托型让用户保留干预能力，同时框架继续保障
 * 检查点 / 取消 / 异常兜底。<br>
 * <br>
 * 典型形态：
 * <pre>{@code
 * @CommandNode("run")
 * @Sandbox(box = LocalSandbox.class, timeoutMs = 5000)
 * public SessionContext run(CommandLine cmd, AgentContext ctx, Sandbox box) {
 *     if (DangerPolicy.isDangerous(cmd)) {
 *         ctx.append(...拒绝...); return ctx;      // 用户可在执行前拦截
 *     }
 *     ExecResult r = box.exec(cmd, ctx);           // 交给沙箱
 *     ...
 * }
 * }</pre>
 *
 * <h2>超时与输出上限：优先级链的第 3 层</h2>
 * 本注解的两个数值属性低于单次调用参数、高于全局配置。完整链条：
 * <ol>
 *     <li>{@code Sandbox#defaultTimeout()} —— 沙箱实现默认（最低）</li>
 *     <li>全局配置 {@code sandbox.<name>.timeout}</li>
 *     <li><b>本注解的 {@link #timeoutMs()}</b></li>
 *     <li>{@code ExecOptions#timeout(Duration)} —— 单次调用（最高）</li>
 * </ol>
 * 用 {@code -1} 表示「此项不覆盖，沿用下层」，与 {@code AgentNode#replay()}
 * 用默认值表达「不声明就取默认」是同一种设计语言。
 * <b>刻意区分「用户没写」与「用户就要这个值」</b>：前者跟随下层变化，后者不跟随。
 *
 * <h2>{@code box} 实例由框架管理</h2>
 * 按 {@link #box()} 的类型在容器中解析 / 创建，<b>默认为单例</b>；
 * {@code Sandbox#close()} 在框架 shutdown 时统一调用一次（幂等）。
 * 用户不要自己在方法体里 {@code new} 沙箱 —— 那样生命周期会脱离框架管理。
 *
 * @see CommandNode
 * @see com.taixu.tiangong.common.sandbox.Sandbox
 */
@Agent
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Sandbox {

    /**
     * 用哪个沙箱实现。<br>
     * 无默认值 —— 标注本注解时必须显式给出，避免「以为隔离了其实没隔离」。
     *
     * <p>不标注本注解的命令节点使用框架注册的默认沙箱（{@code LocalSandbox}，
     * <b>本机直接执行、无隔离</b>）。
     */
    Class<? extends com.taixu.tiangong.common.sandbox.Sandbox> box();

    /**
     * 本节点的默认超时（毫秒）。<br>
     * {@code -1}（默认）= 沿用下层来源（全局配置 → 沙箱的
     * {@code Sandbox#defaultTimeout()}）。<br>
     * 建议显式给出 —— 命令是节点类型里最可能 hang 住的一类。
     *
     * <p>单次调用可用 {@code ExecOptions#timeout(Duration)} 覆盖，优先级更高。
     */
    long timeoutMs() default -1L;

    /**
     * 本节点的输出上限（字节）。<br>
     * {@code -1}（默认）= 沿用下层来源。
     *
     * <p>失控命令（如 {@code find / -type f}）可在数秒内产出 GB 级输出，
     * 撑爆堆或直接超出 LLM 上下文。因此这是<b>保护阈值</b>，不是性能指标。
     */
    int maxOutputBytes() default -1;
}
