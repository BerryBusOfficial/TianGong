package org.wutong.tiangong.annotations.annotation.framework.meta;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 元注解，标记一个注解为 Agent 组件注解。<br>
 * 用于发现和管理 Agent 系统中的各类组件。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
@Framework
public @interface MetaComponent {

}
