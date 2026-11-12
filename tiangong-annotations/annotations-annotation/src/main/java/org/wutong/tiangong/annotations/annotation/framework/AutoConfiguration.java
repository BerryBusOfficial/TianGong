package org.wutong.tiangong.annotations.annotation.framework;

import org.wutong.tiangong.annotations.annotation.framework.meta.Framework;

import java.lang.annotation.*;

/**
 * 自动配置类（对标 Spring Boot @AutoConfiguration）。<br>
 * 仅在 {@code @EnableAutoConfiguration} 开启时被自动扫描注册为容器 Bean；
 * 类内的 @Component 子组件随扫描自动装配。<br>
 * 将作为 {@code @TianGongApplicationStarter} 组合开启的自动装配内容。
 */
@Framework
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AutoConfiguration {
}
