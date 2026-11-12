package com.taixu.tiangong.annotations.processor;

import com.taixu.tiangong.annotations.annotation.definition.BeanDefinition;

/**
 * 组件处理器，TianGongScanner 扫描后对 {@link BeanDefinition} 进行后置处理。<br>
 * 处理完成后 BeanDefinition 会被注册到 BeanDefinitionRegistry
 */
public interface ComponentProcessor {

    void process(BeanDefinition definition);

}
