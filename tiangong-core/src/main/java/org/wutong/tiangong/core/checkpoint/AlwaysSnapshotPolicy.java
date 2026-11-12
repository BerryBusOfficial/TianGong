package org.wutong.tiangong.core.checkpoint;

import org.wutong.tiangong.common.agent.context.AgentContext;
import org.wutong.tiangong.core.agent.AgentNodeDefinition;

/**
 * 默认快照粒度策略：每个节点边界都写检查点。<br>
 * 即引入 {@link SnapshotPolicy} 之前的行为，保证默认不改变任何既有语义。<br>
 * <br>
 * 之所以做成具名类而不是 lambda：框架会在启动日志里打印策略名，
 * lambda 的 {@code getSimpleName()} 是一串不可读的内部标识。
 */
public final class AlwaysSnapshotPolicy implements SnapshotPolicy {

    @Override
    public boolean shouldSnapshot(AgentNodeDefinition node, AgentContext context) {
        return true;
    }

    @Override
    public String toString() {
        return "AlwaysSnapshotPolicy（每个节点边界都写）";
    }
}
