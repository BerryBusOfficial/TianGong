package org.wutong.tiangong.annotations.annotation.framework.enable;

import org.wutong.tiangong.annotations.annotation.framework.enable.meta.FrameworkEnable;

import java.lang.annotation.*;

/**
 * 开启全局异常监听（对齐 Spring @EnableAspectJAutoProxy 的异常切面语义）。<br>
 * 开启后，含 {@code @ListenException} 的方法/类被增强，抛出指定异常时通知 fallback。
 * 允许标注在注解上（如组合进 {@code @TianGongApplicationStarter}）。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@FrameworkEnable
public @interface EnableExceptionListener {

    /**
     * 是否开启（默认 true；false 可关闭而无需注释注解）
     */
    boolean value() default true;
}
