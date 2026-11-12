package com.taixu.tiangong.core.sandbox;

import com.taixu.tiangong.common.agent.context.AgentContext;
import com.taixu.tiangong.common.sandbox.CommandLine;

import java.util.Optional;

/**
 * 命令解析器：从工作流上下文中取出「本次要执行的命令」。<br>
 * <br>
 * <h2>为什么需要这一层</h2>
 * 执行器（{@code AgentLoopExecutor}）只认 {@link AgentContext} 这个抽象接口，
 * 它<b>不知道命令从哪来</b>，而且 {@code AgentContext} 本身<b>没有存放命令的槽位</b>
 * （它只定义 workflowId / isCancelled / copy 等通用能力）。<br>
 * <br>
 * 而不同应用的命令来源完全不同：
 * <ul>
 *     <li>ReAct 循环里，命令来自 LLM 的 tool_call 参数
 *         （典型载体是应用自定义上下文里的 {@code functionArgsMap}）</li>
 *     <li>审批工作流里，命令可能来自上游节点的输出</li>
 *     <li>定时任务里，命令可能来自配置</li>
 * </ul>
 * 把「从上下文取命令」抽成扩展点，框架就能对所有来源一视同仁：
 * 命令节点只需要一个 {@link CommandLine}，不关心它怎么来的。
 *
 * <h2>契约</h2>
 * <ul>
 *     <li>{@link #resolve(AgentContext)} 应为<b>纯读取</b>：不修改上下文、不产生副作用。
 *         同一上下文多次调用应返回一致结果（框架可能在重放路径上重复调用）</li>
 *     <li>解析不出命令时返回 {@link Optional#empty()}，<b>不要抛异常</b>
 *         —— 由调用方决定是跳过本节点还是报错</li>
 *     <li>返回的 {@link CommandLine} 必须是<b>已完成切分</b>的
 *         （用 {@link CommandLine#direct} 或 {@link CommandLine#shell} 构造）。
 *         解析器是<b>唯一</b>适合做参数切分的位置 —— 只有它知道自己的命令来源格式</li>
 * </ul>
 *
 * <h2>没有可用的默认实现 —— 需要用户提供</h2>
 * 框架<b>无法</b>提供通用的默认实现：{@code AgentContext} 是纯抽象接口，
 * 不含任何「待执行命令」字段，也无法假定应用把命令存在哪里。
 * 因此：
 * <ul>
 *     <li>应用应实现本接口并注册为 Bean，框架会自动采用</li>
 *     <li>未提供任何实现时，命令节点会在执行期报明确错误
 *         （而非静默跑到 {@code null} 命令上）—— 见
 *         {@code AgentLoopExecutor} 的 COMMAND 分支</li>
 * </ul>
 *
 * <h2>实现示例（ReAct 场景）</h2>
 * <pre>{@code
 * @Component
 * public class SessionCommandResolver implements CommandLineResolver {
 *     @Override
 *     public Optional<CommandLine> resolve(AgentContext ctx) {
 *         if (!(ctx instanceof SessionContext session)) {
 *             return Optional.empty();
 *         }
 *         Arguments args = session.getFunctionArgsMap().get(session.getNowTool());
 *         String command = args == null ? null : args.getString("command");
 *         // 用 shell 模式以支持管道等语法；若希望避免二次解释，改用 direct(List)
 *         return command == null || command.isBlank()
 *                 ? Optional.empty()
 *                 : Optional.of(CommandLine.shell(command));
 *     }
 * }
 * }</pre>
 */
@FunctionalInterface
public interface CommandLineResolver {

    /**
     * 从上下文解析本次要执行的命令。
     *
     * @param context 工作流上下文
     * @return 解析出的命令；无法解析时为空
     */
    Optional<CommandLine> resolve(AgentContext context);
}
