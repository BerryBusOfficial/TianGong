package com.taixu.tiangong.annotations.annotation.framework.agent.loop.command;

import com.taixu.tiangong.annotations.annotation.Alias;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.AgentNode;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.ReplayPolicy;
import com.taixu.tiangong.annotations.annotation.framework.meta.Agent;
import com.taixu.tiangong.common.sandbox.CommandLine;

import java.lang.annotation.*;

/**
 * 命令节点：把「执行一条外部命令」提升为<b>框架可见的一等公民</b>。<br>
 * <br>
 * <h2>为什么需要专门的节点类型</h2>
 * 命令执行有三件事，只有当成节点才能做对：
 * <ul>
 *     <li><b>进检查点</b>：命令是副作用最重的操作之一，必须留痕。作为普通 COMMON 节点
 *         虽然也会写检查点，但框架无从得知「这是一次命令执行」，无法做针对性处理</li>
 *     <li><b>响应取消</b>：{@code continueFrom} 循环开头会检查
 *         {@code isCancelled()}，但真正能被打动的是<b>沙箱实现内部</b>的轮询
 *         （见 {@code Sandbox} 契约）</li>
 *     <li><b>可见于图</b>：危险命令的审批 / 降级可在图上表达成节点分支，
 *         而不是藏在一个普通节点的方法体里</li>
 * </ul>
 *
 * <h2>方法签名约定</h2>
 * 参数按<b>类型</b>匹配，顺序任意：
 * <ul>
 *     <li>{@link CommandLine} → 注入待执行命令（<b>必须声明</b>）</li>
 *     <li>{@code AgentContext} 或其子类 → 注入当前工作流上下文</li>
 *     <li>{@link com.taixu.tiangong.common.sandbox.Sandbox} → 注入
 *         {@link Sandbox#box()} 指定的实例；未标注 {@link Sandbox} 时注入默认沙箱</li>
 * </ul>
 * 返回 {@code void} 或 {@code AgentContext}（与 COMMON 一致）。<br>
 * <br>
 * <pre>{@code
 * @CommandNode("run")
 * @NodeNext("response")
 * @Sandbox(box = LocalSandbox.class)
 * public SessionContext run(CommandLine cmd, AgentContext ctx, Sandbox box) { ... }
 * }</pre>
 *
 * <h2>{@code replay} 的建议</h2>
 * 命令执行属于<b>含外部副作用</b>的操作。按 {@link ReplayPolicy} 的约定应声明
 * {@code NEVER}，避免崩溃恢复时被重复执行。<br>
 * 默认值 {@code ALWAYS} 是为了与 {@code AgentNode} 的既有行为保持一致
 * —— 但同时对应长沙箱的节点通常该显式声明 {@code NEVER}。
 *
 * @see Sandbox 指定执行环境
 * @see NodeType#COMMAND
 */
@Agent
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@AgentNode(type = NodeType.COMMAND)
public @interface CommandNode {

    @Alias("name")
    String value() default "";

    @Alias("value")
    String name() default "";

    /**
     * 崩溃恢复时可否重放（检查点契约 A5）。<br>
     * 命令执行建议显式声明 {@link ReplayPolicy#NEVER}，避免重复副作用。<br>
     * 本属性由 {@code AbstractNodeProcessor#resolveReplayPolicy} 通过反射读取，
     * 无需在本注解之外再做处理。
     */
    ReplayPolicy replay() default ReplayPolicy.ALWAYS;
}
