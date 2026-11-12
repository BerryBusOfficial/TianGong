package org.wutong.tiangong.annotations.annotation.framework.agent.loop.tool;

import org.wutong.tiangong.annotations.annotation.Alias;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.AgentNode;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.ReplayPolicy;
import org.wutong.tiangong.annotations.annotation.framework.meta.Agent;

import java.lang.annotation.*;

/**
 * 工具节点：把「调用一次工具」提升为<b>框架可见的一等公民</b>。<br>
 * <br>
 * <h2>与 {@code @AgentNode(type = TOOL)} 的关系</h2>
 * 本注解等价于 {@code @AgentNode(type = NodeType.TOOL)}，只是写法更短、语义更明显。
 * 真正带来能力的是<b>底层的通用参数注入</b>——{@code AgentToolInvoker} 作为形参时由框架
 * 从容器取，<b>任何节点类型</b>（START / COMMON / BRANCH …）都能这么用。<br>
 * 标注为 TOOL 额外得到两件事：启动期强制校验签名必须含调用器，以及工作流图上
 * 「哪个节点在调工具」一目了然。
 *
 * <h2>方法签名约定</h2>
 * 参数按<b>类型</b>匹配，顺序任意：
 * <ul>
 *     <li>{@code AgentToolInvoker}（{@code org.wutong.tiangong.core.agent.AgentToolInvoker}）
 *         → 注入工具调用器（<b>必须声明</b>，无需 {@code @Inject}）。<br>
 *         此处不便用 {@code @link}：本模块不依赖 {@code tiangong-core}，
 *         模块依赖方向是 common → annotations → core</li>
 *     <li>{@code AgentContext} 或其子类 → 注入当前工作流上下文（最多一个）</li>
 * </ul>
 * 返回 {@code void} 或 {@code AgentContext}（与 COMMON 一致）。<br>
 * <br>
 * <pre>{@code
 * @ToolNode("tool")
 * @NodeNext("response")
 * public SessionContext tool(SessionContext ctx, AgentToolInvoker invoker) {
 *     Object result = invoker.invoke(ctx.getNowTool(), args);
 *     ...
 * }
 * }</pre>
 *
 * <h2>{@code replay} 的建议</h2>
 * 工具调用通常含外部副作用。按 {@link ReplayPolicy} 的约定应声明 {@code NEVER}，
 * 避免崩溃恢复时被重复执行。<br>
 * 默认值 {@code ALWAYS} 是为了与 {@code AgentNode} 的既有行为保持一致
 * —— 但有副作用的工具节点通常该显式声明 {@code NEVER}。
 *
 * @see NodeType#TOOL
 */
@Agent
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@AgentNode(type = NodeType.TOOL)
public @interface ToolNode {

    @Alias("name")
    String value() default "";

    @Alias("value")
    String name() default "";

    /**
     * 崩溃恢复时可否重放（检查点契约 A5）。<br>
     * 有副作用的工具调用建议显式声明 {@link ReplayPolicy#NEVER}。<br>
     * 本属性由 {@code AbstractNodeProcessor#resolveReplayPolicy} 通过反射读取，
     * 无需在本注解之外再做处理。
     */
    ReplayPolicy replay() default ReplayPolicy.ALWAYS;
}
