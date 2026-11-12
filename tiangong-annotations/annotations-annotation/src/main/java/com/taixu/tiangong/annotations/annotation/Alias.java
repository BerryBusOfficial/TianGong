package com.taixu.tiangong.annotations.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 允许为同一个注解内的两个参数设置为对应别名，一般成对出现，常见于有意义参数与<code>value()</code>之间的映射
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Alias {

    String value();

}
