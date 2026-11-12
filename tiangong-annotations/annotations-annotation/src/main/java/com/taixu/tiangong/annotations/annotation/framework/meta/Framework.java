package com.taixu.tiangong.annotations.annotation.framework.meta;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

/**
 * 标注该注解为框架内注解，需要使用框架上下文介入，若标记在元注解上，允许其元注解下的所有注解都支持该注解说法，该版本下，目前为仅标记
 */
@Documented
@Target(ElementType.ANNOTATION_TYPE)
public @interface Framework {

}
