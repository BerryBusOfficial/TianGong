package org.wutong.tiangong.annotations.annotation.framework;

import org.wutong.tiangong.annotations.annotation.framework.enable.*;
import org.wutong.tiangong.annotations.annotation.framework.meta.Framework;

import java.lang.annotation.*;

/**
 * 主启动注解。<br>
 * 组合开启全部内置功能：异步（@EnableAsync）、跟踪（@EnableTrace）、
 * 自动装配（@EnableAutoConfiguration）、异常监听（@EnableExceptionListener）、
 * API（@EnableApi）、MCP（@EnableMcp）。
 */
@Framework
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@EnableApi
@EnableMcp
@EnableAsync
@EnableTrace
@EnableAutoConfiguration
@EnableExceptionListener
public @interface TianGongApplicationStarter {
}
