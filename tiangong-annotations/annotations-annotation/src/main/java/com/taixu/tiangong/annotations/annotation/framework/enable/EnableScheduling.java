package com.taixu.tiangong.annotations.annotation.framework.enable;

import com.taixu.tiangong.annotations.annotation.framework.enable.meta.FrameworkEnable;

import java.lang.annotation.*;

@Documented
@FrameworkEnable
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
public @interface EnableScheduling {

    boolean value() default true;

}
