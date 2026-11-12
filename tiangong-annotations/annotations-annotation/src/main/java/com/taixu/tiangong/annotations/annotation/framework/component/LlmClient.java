package com.taixu.tiangong.annotations.annotation.framework.component;

import com.taixu.tiangong.annotations.annotation.framework.meta.Framework;
import com.taixu.tiangong.common.type.ProviderType;

import java.lang.annotation.*;

@Framework
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface LlmClient {

    Class<? extends ProviderType> provider();

}
