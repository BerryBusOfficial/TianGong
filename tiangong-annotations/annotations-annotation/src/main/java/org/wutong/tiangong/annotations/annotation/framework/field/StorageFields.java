package org.wutong.tiangong.annotations.annotation.framework.field;

import org.wutong.tiangong.annotations.annotation.framework.meta.Framework;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Framework
public @interface StorageFields {

    /**
     * 存储键（位置参数优先）；为空时回退 {@link #name()}，再回退字段名
     */
    String value() default "";

    String name() default "";

    /**
     * false 时不生成查询字段常量（不参与查询）
     */
    boolean exist() default true;

}
