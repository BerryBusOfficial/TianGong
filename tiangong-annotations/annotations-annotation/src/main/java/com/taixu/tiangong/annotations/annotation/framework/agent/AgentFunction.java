package com.taixu.tiangong.annotations.annotation.framework.agent;

import com.taixu.tiangong.annotations.annotation.framework.meta.Agent;

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
     * 调用该工具所需的权限 scope（如 {@code report:draft}）。<br>
     * 空数组 = 不需要额外权限（保持既有工具兼容）；非空时，调用者上下文必须同时持有
     * 全部 scope，否则工具不会下发给模型、也不会被执行。<br>
     * 通配规则见 {@code AgentCallContext#hasScope}：调用方持有 {@code report:*} 可匹配
     * {@code report:draft}。
     */
    String[] scopes() default {};

    /**
     * 是否必须经过人工/规则审批才可执行。<br>
     * true 时框架调用 {@code ToolApprovalHandler}；<b>没有配置处理器 = 拒绝执行</b>，
     * 避免「声明了审批却静默放行」。
     */
    boolean requiresApproval() default false;

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
