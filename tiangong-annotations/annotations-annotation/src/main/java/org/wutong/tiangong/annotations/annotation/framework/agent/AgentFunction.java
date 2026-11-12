package org.wutong.tiangong.annotations.annotation.framework.agent;

import org.wutong.tiangong.annotations.annotation.framework.meta.Agent;

import java.lang.annotation.*;

/**
 * 标记一个方法为 Agent TOOL 的可调用函数。<br>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Agent
public @interface AgentFunction {

    String name() default "";

    String description() default "";

    /**
     * 标记方法参数，指定参数名和描述供 LLM 识别。
     */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    @interface ToolParam {

        String name() default "";

        String description() default "";

        boolean required() default true;

        /**
         * 当 required 为 false时必须设置，也仅 required 为 false 时该参数有效
         * @return 默认值
         */
        String defaultValue() default "";

    }

}
