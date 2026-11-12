package org.wutong.tiangong.annotations.annotation.framework;

import org.wutong.tiangong.annotations.annotation.framework.meta.Framework;

import java.lang.annotation.*;

@Framework
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface LazyInit {
    boolean value() default true;
}
