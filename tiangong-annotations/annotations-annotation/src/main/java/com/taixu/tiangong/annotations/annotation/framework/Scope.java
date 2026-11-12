package com.taixu.tiangong.annotations.annotation.framework;

import com.taixu.tiangong.annotations.annotation.definition.Lifecycle;
import com.taixu.tiangong.annotations.annotation.framework.meta.Framework;

import java.lang.annotation.*;

@Framework
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Scope {

    /**
     * 生命周期，默认 PROTOTYPE
     */
    Lifecycle value() default Lifecycle.SINGLETON;

}