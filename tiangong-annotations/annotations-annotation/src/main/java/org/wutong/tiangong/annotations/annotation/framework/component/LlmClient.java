package org.wutong.tiangong.annotations.annotation.framework.component;

import org.wutong.tiangong.annotations.annotation.framework.meta.Framework;
import org.wutong.tiangong.common.type.ProviderType;

import java.lang.annotation.*;

@Framework
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface LlmClient {

    Class<? extends ProviderType> provider();

}
