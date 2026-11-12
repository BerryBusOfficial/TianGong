package com.taixu.tiangong.annotations.annotation.framework.enable;

import com.taixu.tiangong.annotations.annotation.framework.enable.meta.FrameworkEnable;

import java.lang.annotation.*;

/**
 * 是否启用目录式技能（{@code SKILL.md}）的自动加载。关闭后即使存在技能目录也不会扫描。<br>
 * 需配合 {@code @EnableAutoConfiguration} 使用：装配由 {@code @AutoConfiguration} 驱动，
 * 本开关控制是否允许 {@code SkillAutoConfiguration} 执行扫描。<br>
 * <br>
 * 注意：这<b>不影响</b> Bean 形态的技能 —— 实现 {@code Skill} 接口的容器 Bean
 * 由框架核心直接收集，与本开关无关。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@FrameworkEnable
public @interface EnableSkill {

    boolean value() default true;

}
