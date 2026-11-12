package org.wutong.tiangong.bytebuddy;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.Map;

public class AgentEnhancer {

    private static final ByteBuddy BYTE_BUDDY = new ByteBuddy();

    /**
     * 静态委托（如不需要实例状态）
     */
    public static <T> Class<? extends T> enhanceClass(
            Class<T> originalClass,
            ElementMatcher<? super MethodDescription> matcher,
            Class<?> interceptor) {
        return BYTE_BUDDY.subclass(originalClass)
                .method(matcher)
                .intercept(MethodDelegation.to(interceptor))
                .make()
                .load(originalClass.getClassLoader(), ClassLoadingStrategy.Default.INJECTION)
                .getLoaded();
    }

    /**
     * 实例委托（拦截器需要持有 registry 等状态）—— @AgentAsync 需要这个
     */
    public static <T> Class<? extends T> enhanceClass(
            Class<T> originalClass,
            ElementMatcher<? super MethodDescription> matcher,
            Object delegationTarget) {
        return BYTE_BUDDY.subclass(originalClass)
                .method(matcher)
                .intercept(MethodDelegation.to(delegationTarget))
                .make()
                .load(originalClass.getClassLoader(), ClassLoadingStrategy.Default.INJECTION)
                .getLoaded();
    }

    /**
     * 链式多拦截器增强：一次增强，多个 matcher → 多个 interceptor。<br>
     * 避免嵌套增强导致子类方法注解丢失（@AgentAsync + @Trace 组合场景）。
     */
    public static <T> Class<? extends T> enhanceClass(
            Class<T> originalClass,
            Map<ElementMatcher<? super MethodDescription>, Object> delegates) {
        DynamicType.Builder<? extends T> builder = BYTE_BUDDY.subclass(originalClass);
        for (Map.Entry<ElementMatcher<? super MethodDescription>, Object> entry : delegates.entrySet()) {
            builder = builder.method(entry.getKey())
                    .intercept(MethodDelegation.to(entry.getValue()));
        }
        return builder.make()
                .load(originalClass.getClassLoader(), ClassLoadingStrategy.Default.INJECTION)
                .getLoaded();
    }
}