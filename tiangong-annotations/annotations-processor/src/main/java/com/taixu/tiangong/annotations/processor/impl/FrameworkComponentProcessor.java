package com.taixu.tiangong.annotations.processor.impl;

import com.taixu.tiangong.annotations.annotation.definition.BeanDefinition;
import com.taixu.tiangong.annotations.annotation.framework.Component;
import com.taixu.tiangong.annotations.annotation.framework.inject.Inject;
import com.taixu.tiangong.annotations.processor.ComponentProcessor;
import com.taixu.tiangong.common.exception.SystemRuntimeException;

import java.lang.reflect.Constructor;
import java.util.Arrays;

@com.taixu.tiangong.annotations.annotation.framework.registrar.ComponentProcessor(processOn = Component.class)
public class FrameworkComponentProcessor implements ComponentProcessor {

    @Override
    public void process(BeanDefinition definition) {
        Component ann = definition.getBeanClass().getAnnotation(Component.class);
        if (ann == null) {
            return;
        }

        validateBeanName(definition, ann);
        validateInstantiable(definition);
    }

    /**
     * 校验：@Component("xxx") 的 BeanName 与 BeanDefinition 一致
     */
    private void validateBeanName(BeanDefinition definition, Component ann) {
        if (!ann.value().isBlank() && !definition.getBeanName().equals(ann.value())) {
            throw new SystemRuntimeException(
                    "BeanName 解析不一致: 注解指定 [" + ann.value()
                            + "] 但 BeanDefinition 为 [" + definition.getBeanName() + "]");
        }
    }

    /**
     * 校验：类可被容器实例化（与 FrameworkContainer.instantiate 规则对齐）
     */
    private void validateInstantiable(BeanDefinition definition) {
        Class<?> clazz = definition.getBeanClass();
        Constructor<?>[] ctors = clazz.getDeclaredConstructors();

        // 规则 1：有 @Inject 构造器 → 可实例化
        for (Constructor<?> ctor : ctors) {
            if (ctor.isAnnotationPresent(Inject.class)) {
                return;
            }
        }

        // 规则 2：无参构造 → 可实例化
        for (Constructor<?> ctor : ctors) {
            if (ctor.getParameterCount() == 0) {
                return;
            }
        }

        // 规则 3：有且仅有一个有参构造器 → 可实例化
        long withArgs = Arrays.stream(ctors).filter(c -> c.getParameterCount() > 0).count();
        if (withArgs == 1) {
            return;
        }

        throw new SystemRuntimeException(
                "组件不可实例化: " + clazz.getName()
                        + "（需要 @Inject 构造器、无参构造器、或唯一有参构造器）");
    }
}
