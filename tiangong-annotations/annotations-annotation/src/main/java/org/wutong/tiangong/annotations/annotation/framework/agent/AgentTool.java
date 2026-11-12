package org.wutong.tiangong.annotations.annotation.framework.agent;

import org.wutong.tiangong.annotations.annotation.framework.Component;
import org.wutong.tiangong.annotations.annotation.framework.meta.Agent;

import java.lang.annotation.*;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Agent
@Component
public @interface AgentTool {

    String value() default "";

}
