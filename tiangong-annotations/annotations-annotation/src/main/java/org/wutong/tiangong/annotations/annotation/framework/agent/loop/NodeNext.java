package org.wutong.tiangong.annotations.annotation.framework.agent.loop;

import org.wutong.tiangong.annotations.annotation.framework.meta.Agent;

import java.lang.annotation.*;

/**
 * 指定节点方法的下一个节点路由位置，必须注解在有@{@link AgentNode}的方法上，除非其NodeType为END
 */
@Agent
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NodeNext {

    String[] value() default {};

}
