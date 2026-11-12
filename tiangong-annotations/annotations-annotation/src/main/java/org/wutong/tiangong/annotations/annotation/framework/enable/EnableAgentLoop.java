package org.wutong.tiangong.annotations.annotation.framework.enable;

import org.wutong.tiangong.annotations.annotation.framework.enable.meta.FrameworkEnable;
import org.wutong.tiangong.common.agent.context.AgentContext;

import java.lang.annotation.*;

/**
 * 开启 Agent Loop 自动调度。开启后框架自行驱动 Agent 节点链循环执行，
 * 类似 SpringBoot 维护 Web 进程：单轮异常不退出，跳过本轮继续下一轮。<br>
 * <br>
 * 退出由用户手动结束进程（如 Ctrl+C），框架不提供主动退出机制。<br>
 * 未标注或 value=false 时不启动调度，用户需手动调用 executor.execute()。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@FrameworkEnable
public @interface EnableAgentLoop {

    boolean value() default true;

    /**
     * 调度使用的 AgentContext 实现类。框架每轮从容器获取该类型的 Bean 执行。
     */
    Class<? extends AgentContext> contextClass();

}
