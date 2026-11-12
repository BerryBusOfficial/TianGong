package com.taixu.tiangong.core.container.process;

import com.taixu.tiangong.annotations.annotation.definition.BeanDefinition;

public interface AnnotationProcessor {

    void process(Object instance, BeanDefinition definition) throws Exception;

}
