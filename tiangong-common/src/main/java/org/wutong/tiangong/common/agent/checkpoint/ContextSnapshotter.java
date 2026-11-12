package org.wutong.tiangong.common.agent.checkpoint;

import org.wutong.tiangong.common.agent.context.AgentContext;

/**
 * 上下文快照器：<b>检查点的核心扩展点</b>（docs/checkpoint-contract.md A3）。<br>
 * <br>
 * <b>为什么必须是接口，而不是直接调 JsonUtil</b>：框架不能假设用户的 {@code AgentContext}
 * 可以被 JSON 序列化。用户的上下文里完全可能存在 {@code byte[]}、{@code InputStream}、
 * 循环引用，或不可序列化的客户端对象（数据库连接、线程池句柄）。
 * 框架若把"整状态快照"直接绑死在 JSON 上，就等于替用户做了一个它无权做的假设。<br>
 * <br>
 * <b>实现约束</b>：<br>
 * <ul>
 *     <li>失败必须抛 {@link org.wutong.tiangong.common.exception.CheckpointException}，
 *         <b>禁止静默降级</b>（返回原对象 / null / 空快照）</li>
 *     <li>{@link #capture} 产出的 {@code contextType} 必须能被 {@link #restore} 还原</li>
 * </ul>
 * <br>
 * 默认实现为 {@link JsonContextSnapshotter}；用户声明同类型的 {@code @Component} 实现即可覆盖。
 */
public interface ContextSnapshotter {

    /** 默认快照结构版本 */
    int DEFAULT_SCHEMA_VERSION = 1;

    /**
     * 当前快照器产出的结构版本号。<br>
     * 状态结构变更（增删字段、改变语义）时必须递增，并配合 {@code SchemaMigrator} 处理旧数据。
     */
    default int schemaVersion() {
        return DEFAULT_SCHEMA_VERSION;
    }

    /**
     * 捕获上下文快照。
     *
     * @param context 当前上下文，不可为 null
     * @return 带版本号与类型信息的快照
     * @throws org.wutong.tiangong.common.exception.CheckpointException 捕获失败
     */
    ContextSnapshot capture(AgentContext context);

    /**
     * 从快照还原上下文。
     *
     * @param snapshot 待还原的快照
     * @param <C>      上下文类型
     * @return 还原出的上下文实例（与 {@link #capture} 时的状态等价）
     * @throws org.wutong.tiangong.common.exception.CheckpointException
     *         结构版本不匹配、类型缺失或反序列化失败
     */
    <C extends AgentContext> C restore(ContextSnapshot snapshot);
}
