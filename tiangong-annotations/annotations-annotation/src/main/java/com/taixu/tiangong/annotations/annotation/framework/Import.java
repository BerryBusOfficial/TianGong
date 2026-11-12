package com.taixu.tiangong.annotations.annotation.framework;

import com.taixu.tiangong.annotations.annotation.framework.meta.Framework;

import java.lang.annotation.*;

/**
 * 声明式导入类并注册为 Bean（对标 Spring @Import）。<br>
 * 标注在任意类上（通常为启动类/配置类）：value 中的类被装配阶段注册——
 * 实现 {@code FrameworkConfiguration} 的类执行 configure；普通类注册为容器 Bean。
 */
@Framework
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Import {

    /**
     * 要导入注册的类（任意位置，非扫描包也可）
     */
    Class<?>[] value();
}
