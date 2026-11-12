package com.taixu.tiangong.annotations.annotation.framework.inject;

import com.taixu.tiangong.annotations.annotation.framework.meta.Framework;
import com.taixu.tiangong.common.enums.InjectSource;
import com.taixu.tiangong.common.enums.RequestType;

import java.lang.annotation.*;

/**
 * 统一注入注解：按 {@link #source()} 分派注入来源。<br>
 * 合并自 @Inject / @InjectLlm / @InjectRequest / @InjectResource。<br>
 * 字段注入支持全部来源；构造器标注仅支持 BEAN（Bean 依赖注入，容器按 @Inject 构造器解析）。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.CONSTRUCTOR})
@Framework
public @interface Inject {

    /**
     * 注入来源标识：<br>
     * BEAN → Bean 名称；LLM → LlmRegistry 配置别名；REQUEST → 请求名；RESOURCE → classpath 资源路径。
     */
    String value() default "";

    /**
     * 注入来源类型，默认 BEAN（Bean 注入，兼容原 @Inject）
     */
    InjectSource source() default InjectSource.BEAN;

    /**
     * source = REQUEST 时的请求构建器类型（CHAT / EMBEDDING）
     */
    RequestType request() default RequestType.CHAT;

}
