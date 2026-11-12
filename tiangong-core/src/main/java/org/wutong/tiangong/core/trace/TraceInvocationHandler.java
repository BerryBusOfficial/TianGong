package org.wutong.tiangong.core.trace;

import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.implementation.bind.annotation.*;
import org.wutong.tiangong.annotations.annotation.framework.trace.Trace;
import org.wutong.tiangong.common.exception.SystemRuntimeException;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * @Trace 方法拦截器：方法入口/出口/异常事件 + 字段快照对比 + 计时/超时回调。<br>
 * 无 handler 注册时走快速路径（零开销）。
 */
@Slf4j
public class TraceInvocationHandler {

    private final TraceHandlerRegistry registry;

    public TraceInvocationHandler(TraceHandlerRegistry registry) {
        this.registry = registry;
    }

    @RuntimeType
    public Object intercept(@This Object target, @Origin Method method,
                            @AllArguments Object[] args, @SuperCall Callable<?> callable) throws Exception {
        if (registry.size() == 0) {
            return callable.call();                      // 快速路径：无订阅者
        }
        String targetName = method.getDeclaringClass().getSimpleName() + "." + method.getName();
        long start = System.nanoTime();

        Map<Field, Object> snapshot = snapshotFields(method, target);
        registry.forEach(handler -> handler.onMethodEntry(
                new TraceMethodEvent(targetName, method.getDeclaringClass(), target, args, null, start, 0)));

        try {
            Object result = callable.call();
            long elapsed = System.nanoTime() - start;
            registry.forEach(handler -> handler.onMethodExit(
                    new TraceMethodEvent(targetName, method.getDeclaringClass(), target, args, result, start, elapsed)));
            fireTiming(method, targetName, elapsed);
            fireFieldWrites(method, target, snapshot, targetName);
            return result;
        } catch (Throwable t) {
            long elapsed = System.nanoTime() - start;
            registry.forEach(handler -> handler.onMethodError(
                    new TraceMethodEvent(targetName, method.getDeclaringClass(), target, args, null, start, elapsed), t));
            fireTiming(method, targetName, elapsed);
            if (t instanceof Exception e) {
                throw e;
            }
            throw new SystemRuntimeException(t);
        }
    }

    /**
     * 触发计时回调：每次方法结束调 onMethodTimed；配置了 timeout 且超限时再调 onMethodTimeout。
     */
    private void fireTiming(Method method, String targetName, long elapsedNanos) {
        Trace trace = method.getAnnotation(Trace.class);
        long timeout = trace != null ? trace.timeout() : 0;
        TimeUnit unit = trace != null ? trace.unit() : TimeUnit.MILLISECONDS;
        long timeoutNanos = timeout > 0 ? unit.toNanos(timeout) : 0;

        TimerEvent event = new TimerEvent(targetName, method.getDeclaringClass(),
                elapsedNanos, timeoutNanos, timeout, unit);

        registry.forEach(handler -> handler.onMethodTimed(event));
        if (timeout > 0 && elapsedNanos > timeoutNanos) {
            registry.forEach(handler -> handler.onMethodTimeout(event));
        }
    }

    /**
     * 入口：快照被跟踪类中所有 @Trace 字段，并触发 onFieldRead
     */
    private Map<Field, Object> snapshotFields(Method method, Object target) {
        Map<Field, Object> snapshot = new HashMap<>();
        if (target == null || Modifier.isStatic(method.getModifiers())) {
            return snapshot;
        }
        for (Field field : method.getDeclaringClass().getDeclaredFields()) {
            Trace trace = field.getAnnotation(Trace.class);
            if (trace == null) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(target);
                snapshot.put(field, value);
                registry.forEach(handler -> handler.onFieldRead(new TraceFieldEvent(
                        field.getName(), method.getDeclaringClass(), target, null, value, System.currentTimeMillis())));
            } catch (IllegalAccessException e) {
                log.warn("Trace 字段读取失败: {}.{}", method.getDeclaringClass().getSimpleName(), field.getName());
            }
        }
        return snapshot;
    }

    /**
     * 出口：对比快照，变化的字段触发 onFieldWrite
     */
    private void fireFieldWrites(Method method, Object target, Map<Field, Object> snapshot, String targetName) {
        if (snapshot.isEmpty() || target == null) {
            return;
        }
        for (Map.Entry<Field, Object> entry : snapshot.entrySet()) {
            Field field = entry.getKey();
            try {
                field.setAccessible(true);
                Object newValue = field.get(target);
                if (!Objects.equals(entry.getValue(), newValue)) {
                    registry.forEach(handler -> handler.onFieldWrite(new TraceFieldEvent(
                            field.getName(), method.getDeclaringClass(), target,
                            entry.getValue(), newValue, System.currentTimeMillis())));
                }
            } catch (IllegalAccessException e) {
                log.warn("Trace 字段对比失败: {}.{}", method.getDeclaringClass().getSimpleName(), field.getName());
            }
        }
    }
}
