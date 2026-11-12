package org.wutong.tiangong.common.agent.checkpoint;

/**
 * 上下文快照：检查点中可持久化的状态载体。<br>
 * <br>
 * <b>契约（docs/checkpoint-contract.md A4）</b>：快照必须自带 {@code schemaVersion} 与
 * {@code contextType}。前者用于识别状态结构版本，后者用于还原时定位实现类
 * （用户的 {@code AgentContext} 子类通常不在框架的默认包内，且可能是动态加载的）。
 *
 * @param schemaVersion 产出该快照的 {@link ContextSnapshotter} 版本号
 * @param contextType   {@code AgentContext} 实现类的全限定名
 * @param payload       由 {@link ContextSnapshotter} 定义的负载（默认实现为 JSON 文本）
 */
public record ContextSnapshot(
        int schemaVersion,
        String contextType,
        String payload
) {

    public ContextSnapshot {
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion 必须为正数: " + schemaVersion);
        }
        if (contextType == null || contextType.isBlank()) {
            throw new IllegalArgumentException("contextType 不能为空");
        }
        if (payload == null) {
            throw new IllegalArgumentException("payload 不能为 null（空快照是非法状态）");
        }
    }
}
