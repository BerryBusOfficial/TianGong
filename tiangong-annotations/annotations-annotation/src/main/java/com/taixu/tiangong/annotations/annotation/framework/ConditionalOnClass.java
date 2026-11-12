package com.taixu.tiangong.annotations.annotation.framework;

import com.taixu.tiangong.annotations.annotation.framework.meta.Framework;

import java.lang.annotation.*;

/**
 * 条件装配：类上标注后，仅当 {@link #value()} 中所有全限定类名在 classpath 存在时才注册该 Bean（对标 Spring Boot @ConditionalOnClass）。<br>
 * 使用 String 类名而非 Class 字面量——可选依赖在编译期可能不存在。
 */
@Framework
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ConditionalOnClass {

    /**
     * 全限定类名（运行时 Class.forName 判断存在性）
     */
    String[] value();
}
