package com.taixu.tiangong.annotations.annotation.framework.agent;

import com.taixu.tiangong.annotations.annotation.framework.Component;
import com.taixu.tiangong.annotations.annotation.framework.meta.Agent;

import java.lang.annotation.*;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Agent
@Component
public @interface AgentTool {

    String value() default "";

    /**
     * 工具类级别的默认 scope：类里所有 {@code @AgentFunction} 都会继承这组权限。<br>
     * 方法级 {@code scopes} 与类级合并（取并集），不覆盖 —— 避免子方法意外放宽类级约束。
     */
    String[] scopes() default {};

}
