package org.wutong.tiangong.core.container;

/**
 * IoC 容器接口，提供 Bean 的获取与管理能力。<br>
 * 内部处理实例化、生命周期、依赖注入。
 */
public interface Container {

    <T> T getBean(String name);

    <T> T getBean(String name, Class<T> type);

    <T> T getBean(Class<T> type);

    void register(String name, Object instance);

    void refresh();
}
