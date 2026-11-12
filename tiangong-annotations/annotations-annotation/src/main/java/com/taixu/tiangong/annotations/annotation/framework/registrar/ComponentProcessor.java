package com.taixu.tiangong.annotations.annotation.framework.registrar;

import com.taixu.tiangong.annotations.annotation.framework.meta.Processor;

import java.lang.annotation.*;

/**
 * 标记组件处理器，统一注册到中央注册表。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Processor
public @interface ComponentProcessor {

    Class<? extends Annotation> processOn();

}
