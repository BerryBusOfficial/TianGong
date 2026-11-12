package org.wutong.tiangong.core.thread;

import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;
import org.wutong.tiangong.annotations.annotation.framework.component.Async;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * &#064;Async  方法拦截器：按注解指定的 executor 提交任务。
 */
@Slf4j
public class AsyncInvocationHandler {

    private final TaskExecutorRegistry registry;

    public AsyncInvocationHandler(TaskExecutorRegistry registry) {
        this.registry = registry;
    }

    @RuntimeType
    public Object intercept(@Origin Method method, @SuperCall Callable<?> callable) throws Exception {
        Async ann = method.getAnnotation(Async.class);
        TaskExecutor executor = resolveExecutor(ann);
        Class<?> returnType = method.getReturnType();

        if (returnType == void.class || returnType == Void.class) {
            executor.submit(callable);       // fire-and-forget
            return null;
        }
        if (Future.class.isAssignableFrom(returnType)) {
            // 拍平：任务在 executor 执行，调用方 get() 直接拿到方法返回值（而非"返回值的 Future"）
            return executor.submit(() -> {
                try {
                    Object value = callable.call();
                    if (value instanceof Future<?> inner) {
                        return inner.get();
                    }
                    return value;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ExecutionException("异步任务被中断", e);
                } catch (ExecutionException | RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new ExecutionException("异步任务执行失败", e);
                }
            });
        }
        log.warn("@Async 方法必须返回 void 或 Future，当前同步执行: {}.{}",
                method.getDeclaringClass().getSimpleName(), method.getName());
        return callable.call();                          // Spring 行为：同步
    }

    private TaskExecutor resolveExecutor(Async ann) {
        if (ann == null || ann.value().isBlank()) {
            return registry.get("default").orElseThrow(
                    () -> new IllegalStateException("未找到默认 TaskExecutor"));
        }
        return registry.get(ann.value()).orElseThrow(
                () -> new IllegalArgumentException("未注册的 TaskExecutor: " + ann.value()));
    }
}
