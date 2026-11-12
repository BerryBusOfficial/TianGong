package org.wutong.tiangong.spring.bridge;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import org.wutong.tiangong.common.exception.SystemRuntimeException;

/**
 * 延迟 JDK 代理：接口类型的 {@code @TianGongWrite} 注入点在天工就绪前使用，
 * 调用时若目标未回填 → 明确报错（而不是 NPE）；回填后转发真实实例。<br>
 * <br>
 * 与 {@link org.wutong.tiangong.bytebuddy.DeferredInstancePlaceholder} 分工：
 * 接口走本类（JDK 代理），具体类走 ByteBuddy 占位。
 */
final class DeferredJdkProxy {

    private DeferredJdkProxy() {
    }

    static Object create(Class<?> contract) {
        return Proxy.newProxyInstance(
                contract.getClassLoader(),
                new Class<?>[]{contract},
                new Handler(contract));
    }

    static void setTarget(Object proxy, Object target) {
        handlerOf(proxy).target.set(target);
    }

    static Object targetOf(Object proxy) {
        return handlerOf(proxy).target.get();
    }

    private static Handler handlerOf(Object proxy) {
        InvocationHandler handler = Proxy.getInvocationHandler(proxy);
        if (!(handler instanceof Handler h)) {
            throw new SystemRuntimeException("不是延迟代理实例: " + proxy.getClass().getName());
        }
        return h;
    }

    private static final class Handler implements InvocationHandler {

        private final Class<?> contract;
        private final AtomicReference<Object> target = new AtomicReference<>();

        private Handler(Class<?> contract) {
            this.contract = contract;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "DeferredProxy[" + contract.getSimpleName() + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
                    default -> null;
                };
            }
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            Object delegate = target.get();
            if (delegate == null) {
                throw new SystemRuntimeException(
                        "组件尚未就绪：延迟代理被调用 [" + contract.getSimpleName() + "." + method.getName()
                                + "] —— 真实实例尚未回填（宿主自举未完成，或依赖解析失败）");
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause() == null ? e : e.getCause();
            }
        }
    }
}
