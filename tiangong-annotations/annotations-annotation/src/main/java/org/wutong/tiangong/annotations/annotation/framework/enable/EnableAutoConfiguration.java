package org.wutong.tiangong.annotations.annotation.framework.enable;

import org.wutong.tiangong.annotations.annotation.framework.enable.meta.FrameworkEnable;

import java.lang.annotation.*;

/**
 * 开启自动装配配置（对标 Spring Boot @EnableAutoConfiguration）。<br>
 * 开启后标 {@code @AutoConfiguration} 的配置类被自动扫描注册为容器 Bean。<br>
 * 允许标注在注解上（如组合进 {@code @TianGongApplicationStarter}）。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@FrameworkEnable
public @interface EnableAutoConfiguration {

    /**
     * 是否开启（默认 true；false 可关闭而无需注释注解）
     */
    boolean value() default true;
}
