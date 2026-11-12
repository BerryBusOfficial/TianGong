package com.taixu.tiangong.annotations.annotation.definition;

import lombok.Builder;
import lombok.Getter;
import com.taixu.tiangong.annotations.annotation.framework.meta.MetaComponent;

/**
 * Bean 定义，描述一个被 {@link MetaComponent} 系列注解标记的组件。<br>
 * 由 BeanDefinitionFactory 创建，
 * 最终注册到 BeanDefinitionRegistry。
 */
@Getter
@Builder
public class BeanDefinition {

    private final String beanName;
    private final Class<?> beanClass;
    private final Lifecycle lifecycle;
    private final boolean lazyInit;

}
