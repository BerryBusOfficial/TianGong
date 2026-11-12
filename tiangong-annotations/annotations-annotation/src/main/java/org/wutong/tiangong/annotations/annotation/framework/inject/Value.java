package org.wutong.tiangong.annotations.annotation.framework.inject;

import org.wutong.tiangong.annotations.annotation.framework.meta.Framework;
import org.wutong.tiangong.common.exception.ConfigKeyNoExistException;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Framework
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD})
public @interface Value {

    /**
     * 配置键
     *
     * @return 配置键
     */
    String value();

    /**
     * 默认值
     *
     * @return 默认值
     * @throws ConfigKeyNoExistException 为 empty 时抛出异常
     */
    String defaultValue() default "";

}
