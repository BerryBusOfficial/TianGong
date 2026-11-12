package com.taixu.tiangong.annotations.annotation.framework.inject;

import com.taixu.tiangong.annotations.annotation.framework.meta.Framework;

import java.lang.annotation.*;

/**
 * 指定注入候选的 Bean 名。<br>
 * 可标在构造器参数或字段上，与 {@link Inject} 配合：
 * <ul>
 *   <li>构造器参数：{@code @Inject public X(@Qualifier("bean") Dep d) }</li>
 *   <li>字段：{@code @Inject @Qualifier("bean") private Dep d; }</li>
 * </ul>
 * 存在时优先于按类型解析。
 */
@Framework
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.FIELD})
public @interface Qualifier {

    /**
     * Bean 名（必填）
     */
    String value();

}