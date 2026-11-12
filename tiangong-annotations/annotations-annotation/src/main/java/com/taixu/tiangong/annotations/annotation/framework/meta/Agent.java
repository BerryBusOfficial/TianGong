package com.taixu.tiangong.annotations.annotation.framework.meta;

import java.lang.annotation.*;

/**
 * 元注解，标记该注解为Agent方面使用的注解，在目前版本中，该注解为仅标记
 */
@Framework
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface Agent {
}
