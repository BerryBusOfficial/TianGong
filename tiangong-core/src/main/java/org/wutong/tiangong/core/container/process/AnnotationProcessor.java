package org.wutong.tiangong.core.container.process;

import org.wutong.tiangong.annotations.annotation.definition.BeanDefinition;

public interface AnnotationProcessor {

    void process(Object instance, BeanDefinition definition) throws Exception;

}
