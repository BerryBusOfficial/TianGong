package org.wutong.tiangong.annotations.annotation.framework;

import org.wutong.tiangong.annotations.annotation.framework.meta.Framework;

import java.lang.annotation.*;

@Framework
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Primary {

    /**
     * 优先级：值越小优先级越高
     */
    int value() default 0;

}
