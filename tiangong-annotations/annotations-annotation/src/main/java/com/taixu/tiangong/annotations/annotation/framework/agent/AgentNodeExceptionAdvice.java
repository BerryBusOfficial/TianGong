package com.taixu.tiangong.annotations.annotation.framework.agent;

import com.taixu.tiangong.annotations.annotation.framework.meta.Agent;
import com.taixu.tiangong.annotations.annotation.framework.meta.Framework;

import java.lang.annotation.*;

/**
 * 标记这个类为AgentLoop中的指定（若有指定）的Node类下的每个node的事实捕获，配合{@link NodeExceptionHandler}使用
 */
@Agent
@Documented
@Framework
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AgentNodeExceptionAdvice {

    /**
     * 指定捕获在列表内的类中的Node执行错误问题，当为空的时候，为全局捕获
     * @return 类.class列表
     */
    Class<?>[] onClass() default {};

}
