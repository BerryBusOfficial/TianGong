package com.taixu.tiangong.annotations.annotation.framework.enable;

import com.taixu.tiangong.annotations.annotation.framework.component.Async;
import com.taixu.tiangong.annotations.annotation.framework.enable.meta.FrameworkEnable;

import java.lang.annotation.*;

/**
 * 开启 Agent 异步功能（对齐 Spring @EnableAsync）。<br>
 * 标注在任意类上（通常为启动类）：未标注时 {@link Async}
 * 方法保持同步执行；标注后容器对含 @Async 方法的 Bean 进行增强，异步提交到 TaskExecutor。<br>
 * 允许标注在注解上（如组合进 {@code @TianGongApplicationStarter}）。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@FrameworkEnable
public @interface EnableAsync {

    /**
     * 是否开启（默认 true；false 可关闭而无需注释注解）
     */
    boolean value() default true;
}
