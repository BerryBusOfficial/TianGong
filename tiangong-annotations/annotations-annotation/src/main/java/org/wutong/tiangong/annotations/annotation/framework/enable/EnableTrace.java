package org.wutong.tiangong.annotations.annotation.framework.enable;

import org.wutong.tiangong.annotations.annotation.framework.enable.meta.FrameworkEnable;

import java.lang.annotation.*;

/**
 * 开启 Trace 跟踪功能。<br>
 * 允许标注在注解上（如组合进 {@code @TianGongApplicationStarter}）。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@FrameworkEnable
public @interface EnableTrace {

    /**
     * 是否开启（默认 true；false 可关闭而无需注释注解）
     */
    boolean value() default true;
}
