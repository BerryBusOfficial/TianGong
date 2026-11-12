package com.taixu.tiangong.annotations.annotation.framework.enable;

import com.taixu.tiangong.annotations.annotation.framework.enable.meta.FrameworkEnable;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 开启 API HTTP 服务（对应 SpringBoot @Controller 层是否生效）。<br>
 * 开启后 ApiHttpServer 启动监听，工作流执行到 @ApiEntranceNode 时动态注册路由。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.ANNOTATION_TYPE, ElementType.TYPE})
@FrameworkEnable
public @interface EnableApi {

    boolean value() default true;

}
