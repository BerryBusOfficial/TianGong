package org.wutong.tiangong.core.checkpoint;

import org.wutong.tiangong.common.agent.context.AgentContext;
import org.wutong.tiangong.core.agent.AgentNodeDefinition;

/**
 * 快照粒度策略：<b>属策略，下放给用户</b>（docs/checkpoint-contract.md「策略下放，契约上收」）。<br>
 * <br>
 * 决定"哪些节点边界要写检查点"。框架只提供本接口与一个默认实现
 * （{@link #ALWAYS}：每个节点边界都写，即引入本接口之前的行为），
 * <b>不提供 keepLast / 时间窗等任何预设策略</b> —— 取舍归用户。<br>
 * <br>
 * 典型用法（自行声明一个 {@code @Component} 实现即可覆盖默认）：
 * <pre>{@code
 * @Component
 * public class TurnOnlyPolicy implements SnapshotPolicy {
 *     // 只在入口节点与结束节点留档：轮内过程节点不占存储
 *     public boolean shouldSnapshot(AgentNodeDefinition node, AgentContext context) {
 *         var t = node.type();
 *         return t == NodeType.ENTRANCE || t == NodeType.END;
 *     }
 * }
 * }</pre>
 *
 * <b>必须知道的两个后果</b>：<br>
 * <ul>
 *     <li>跳过某节点后，恢复点会是<b>更早</b>的一个 COMPLETED 节点，
 *         中间那些节点在崩溃后会被重跑（语义仍由 A1/A5 保证）。</li>
 *     <li>若跳过 <b>ENTRANCE</b> 节点，则失去"正在等外部输入"的中断点（A8），
 *         跨重启 HITL 不再可用。这是策略选择，框架不拦，只在此说明。</li>
 * </ul>
 *
 * @see CheckpointService#snapshotPolicy()
 */
@FunctionalInterface
public interface SnapshotPolicy {

    /**
     * 该节点边界是否写检查点。
     *
     * @param node    即将执行的节点
     * @param context 当前上下文（可据此判断状态，例如按轮次、按消息数节流）
     * @return true 写；false 跳过（不产生记录，也不影响执行）
     */
    boolean shouldSnapshot(AgentNodeDefinition node, AgentContext context);

    /** 默认策略：每个节点边界都写（保持引入本接口之前的行为） */
    SnapshotPolicy ALWAYS = new AlwaysSnapshotPolicy();
}
