package org.wutong.tiangong.annotations.processor.registry;

/**
 * Aware 回调接口，实现此接口的 Processor 在自动注册时会被注入 {@link BeanDefinitionRegistry} 引用。
 */
public interface RegistryAware {

    void setRegistry(BeanDefinitionRegistry registry);
}
