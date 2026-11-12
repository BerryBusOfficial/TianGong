package org.wutong.tiangong.annotations.annotation.framework.exception;

import org.wutong.tiangong.annotations.annotation.framework.meta.Framework;

import java.lang.annotation.*;

/**
 * 异常监听：方法或类标注。<br>
 * 方法级 → 监听该方法是否抛出指定异常；类级 → 监听类中全部方法。<br>
 * 抛出 {@link #exception()} 指定的异常时，调用 {@link #fallback()} 类（必须是被框架标记的
 * Bean 且实现 {@link ExceptionFallback}）的 onException 通知；监听后异常<b>重新抛出</b>
 */
@Documented
@Framework
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface ListenException {

    /**
     * 监听的目标异常类型（必须继承 Throwable）
     */
    Class<? extends Throwable> exception();

    /**
     * fallback 处理类：必须是被框架标记的类（如 @Component）且实现 {@link ExceptionFallback}；<br>
     * 不传时使用默认 {@link DefaultExceptionFallback}（log.error + 抛 SystemRuntimeException）。
     */
    Class<?> fallback() default DefaultExceptionFallback.class;

}
