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

}
