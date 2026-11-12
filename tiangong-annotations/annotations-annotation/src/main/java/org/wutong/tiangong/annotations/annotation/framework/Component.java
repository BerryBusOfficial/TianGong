package org.wutong.tiangong.annotations.annotation.framework;


import org.wutong.tiangong.annotations.annotation.framework.meta.MetaComponent;

import java.lang.annotation.*;

/**
 * 组件注解：标它的类被扫描注册为容器 Bean。<br>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
@MetaComponent
public @interface Component {

    String value() default "";

}
