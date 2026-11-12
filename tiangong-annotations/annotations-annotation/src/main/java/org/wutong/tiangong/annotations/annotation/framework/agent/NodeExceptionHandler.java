package org.wutong.tiangong.annotations.annotation.framework.agent;

import org.wutong.tiangong.annotations.annotation.framework.meta.Agent;
import org.wutong.tiangong.annotations.annotation.framework.meta.Framework;

import java.lang.annotation.*;

/**
 * 标记某个指定方法为Node运行时出现错误的捕获处理，必须在{@link AgentNodeExceptionAdvice}下才能生效
 */
@Documented
@Agent
@Framework
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NodeExceptionHandler {

    /**
     * 指定需要处理的错误异常，支持多个
     * @return 异常错误类
     */
    Class<? extends Exception>[] exception();

}
