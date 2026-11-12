package com.taixu.tiangong.annotations.annotation.framework.enable.meta;

import com.taixu.tiangong.annotations.annotation.framework.meta.Framework;

import java.lang.annotation.*;

/**
 * 元注解，标记一个注解为框架功能开关（@Enable* 注解）。<br>
 * 被此元注解标注的注解（如 @EnableAsync）扫描时被识别，
 * 标注了这些 @Enable* 注解的用户类会被收集，并在容器创建后执行装配。<br>
 * <br>
 * {@link #value()} 可选：
 * <ul>
 *     <li>不配置：该 Enable 为纯标记，由框架内部功能自行感知</li>
 *     <li>配置：可传任意类——实现 {@code FrameworkConfiguration} 的类执行 configure 装配，
 *     普通类注册为容器 Bean</li>
 * </ul>
 */
@Framework
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface FrameworkEnable {

    /**
     * 功能装配类（可选）
     */
    Class<?>[] value() default {};
}
