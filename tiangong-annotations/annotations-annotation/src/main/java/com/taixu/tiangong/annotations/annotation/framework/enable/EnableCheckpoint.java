package com.taixu.tiangong.annotations.annotation.framework.enable;

import com.taixu.tiangong.annotations.annotation.framework.enable.meta.FrameworkEnable;

import java.lang.annotation.*;

/**
 * 开启检查点功能（office/checkpoint-contract.md）。<br>
 * <br>
 * 未开启时框架不产生任何检查点记录，执行器中的检查点调用为空操作，
 * 行为与引入检查点之前完全一致。<br>
 * <br>
 * 开启后：<br>
 * <ul>
 *     <li>每个节点边界写入 STARTED / COMPLETED / FAILED 记录（不变量 A1）</li>
 *     <li>记录迁移决策与选中的出边（不变量 A2）</li>
 *     <li>快照经 {@code ContextSnapshotter} 产出，默认 JSON 实现（不变量 A3）</li>
 * </ul>
 * <br>
 * 可通过声明 {@code ContextSnapshotter} / {@code CheckpointStore} 的 {@code @Component}
 * 实现来覆盖默认实现。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@FrameworkEnable
public @interface EnableCheckpoint {

    /**
     * 是否开启（默认 true；false 可关闭而无需注释注解）
     */
    boolean value() default true;
}
