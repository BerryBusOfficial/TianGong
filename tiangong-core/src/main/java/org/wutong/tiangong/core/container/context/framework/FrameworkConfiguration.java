package org.wutong.tiangong.core.container.context.framework;

/**
 * 功能装配类接口：由 @Enable* 注解的 {@code value()} 引用。<br>
 * 容器创建后实例化并调用 {@link #configure}，通过 {@link FrameworkConfigurator}
 * 访问框架组件、注册额外 Bean 与处理器。
 */
public interface FrameworkConfiguration {

    void configure(FrameworkConfigurator configurator);
}
