package org.wutong.tiangong.core.exception;

import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;
import org.wutong.tiangong.annotations.annotation.framework.exception.DefaultExceptionFallback;
import org.wutong.tiangong.annotations.annotation.framework.exception.ExceptionFallback;
import org.wutong.tiangong.annotations.annotation.framework.exception.ListenException;
import org.wutong.tiangong.core.container.context.ContextHolder;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;

/**
 * @ListenException 方法拦截器：抛出目标异常时从容器取 fallback Bean 通知，随后重新抛出。<br>
 * 方法级注解优先；方法无注解时回退到声明类的类级注解。
 */
@Slf4j
public class ExceptionListenerInvocationHandler {

    @RuntimeType
    public Object intercept(@Origin Method method, @SuperCall Callable<?> callable) throws Exception {
        try {
            return callable.call();
        } catch (Throwable t) {
            ListenException listen = resolveListen(method);
            if (listen != null && listen.exception().isInstance(t)) {
                Throwable toThrow = notifyFallback(listen, t);
                if (toThrow instanceof Exception e) {
                    throw e;
                }
                throw new RuntimeException(toThrow);
            }
            if (t instanceof Exception e) {
                throw e;
            }
            throw new RuntimeException(t);
        }
    }

    private ListenException resolveListen(Method method) {
        ListenException methodLevel = method.getAnnotation(ListenException.class);
        if (methodLevel != null) {
            return methodLevel;
        }
        return method.getDeclaringClass().getAnnotation(ListenException.class);
    }

    /**
     * 通知 fallback；返回实际应传播的异常：<br>
     * fallback 未抛异常 → 原异常；fallback 抛出异常 → fallback 的异常（默认 fallback 抛 SystemRuntimeException）。
     */
    private Throwable notifyFallback(ListenException listen, Throwable error) {
        try {
            Object fallback = listen.fallback() == DefaultExceptionFallback.class
                    ? DefaultExceptionFallback.INSTANCE            // 默认 fallback（无状态单例，非 Bean）
                    : ContextHolder.getApplication().getContainer().getBean(listen.fallback());
            if (fallback instanceof ExceptionFallback handler) {
                handler.onException(error);
            } else {
                log.warn("fallback 类 {} 未实现 ExceptionFallback，忽略异常监听", listen.fallback().getName());
            }
            return error;
        } catch (RuntimeException e) {
            return e;                                              // fallback 抛出的异常 → 传播它
        } catch (Exception e) {
            log.error("异常监听 fallback 调用失败: {}", listen.fallback().getName(), e);
            return error;
        }
    }
}
