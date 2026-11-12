package com.taixu.tiangong.common.agent.checkpoint;

import com.taixu.tiangong.common.agent.context.AgentContext;

import java.lang.annotation.*;

/**
 * 声明「此成员不属于状态快照」（office/checkpoint-contract.md D1）。<br>
 * <br>
 * <b>判断标准不是「是否由容器注入」，而是「它是否是可还原的状态」</b>：<br>
 * <ul>
 *     <li>应标注：容器管理的<b>单例 Bean</b>、外部句柄、连接、无界集合 ——
 *         它们被快照后还原出的只是一个<b>游离副本</b>，
 *         此后对副本的写入不会被原持有者看到（语义分叉）；无界集合还会让快照成本二次增长。</li>
 *     <li>不应标注：可还原的纯值（配置项、计数、消息列表），即使它来自 {@code @Value} 注入。</li>
 * </ul>
 * <br>
 * <b>还原后的责任</b>：被忽略的成员在还原出的上下文里是<b>未初始化</b>的（null / 默认值）。
 * 应用必须重新提供它 —— 实现 {@link AgentContext#afterRestore()}，
 * 框架在每次从检查点还原后都会调用该钩子。<br>
 * <br>
 * 默认的 {@link JsonContextSnapshotter} 通过 {@code AnnotationIntrospector} 支持本注解。
 * 自定义快照器如不支持，应至少保证"被忽略的成员在还原后不被旧值覆盖"。
 *
 * @see ContextSnapshotter
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface SnapshotIgnore {
}
