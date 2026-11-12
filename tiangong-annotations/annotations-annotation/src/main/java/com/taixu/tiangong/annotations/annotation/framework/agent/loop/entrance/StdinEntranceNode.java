package com.taixu.tiangong.annotations.annotation.framework.agent.loop.entrance;

import com.taixu.tiangong.annotations.annotation.Alias;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.AgentNode;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeType;

import java.lang.annotation.*;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@AgentNode(type = NodeType.ENTRANCE)
public @interface StdinEntranceNode {

    @Alias("name")
    String value() default "";

    @Alias("value")
    String name() default "";

    /**
     * 用于显示输入开始，也可以为“”，这样在Stdin之前不会有任何的显示
     * @return 输入提示
     */
    String inputShow() default ">>> ";

}