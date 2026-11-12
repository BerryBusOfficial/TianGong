package org.wutong.tiangong.annotations.annotation.framework.enable;

import org.wutong.tiangong.annotations.annotation.framework.enable.meta.FrameworkEnable;

import java.lang.annotation.*;

/**
 * 是否启用 MCP 功能。关闭后即使配置了 agent.mcp 也不会加载 MCP server。<br>
 * 需配合 @EnableAutoConfiguration 使用：MCP 装配由 @AutoConfiguration 驱动，
 * 本开关控制是否允许 McpAutoConfiguration 执行加载逻辑。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@FrameworkEnable
public @interface EnableMcp {

    boolean value() default true;

}
