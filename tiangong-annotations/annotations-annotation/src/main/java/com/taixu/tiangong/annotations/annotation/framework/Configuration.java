package com.taixu.tiangong.annotations.annotation.framework;

import com.taixu.tiangong.annotations.annotation.framework.meta.Framework;

import java.lang.annotation.*;

/**
 * 声明式配置绑定组件：按 prefix 前缀从配置树批量绑定字段。<br>
 * 字段名自动转 SNAKE_CASE（baseUrl → base_url）；缺失的配置键置 null 并输出警告。
 */
@Framework
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Component
public @interface Configuration {

    /**
     * 配置前缀（如 "agent.llm"）
     */
    String prefix();
}
