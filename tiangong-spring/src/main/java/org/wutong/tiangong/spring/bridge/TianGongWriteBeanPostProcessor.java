package org.wutong.tiangong.spring.bridge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.util.StringUtils;
import org.wutong.tiangong.annotations.annotation.framework.inject.Qualifier;
import org.wutong.tiangong.bytebuddy.DeferredInstancePlaceholder;
import org.wutong.tiangong.common.exception.SystemRuntimeException;
import org.wutong.tiangong.spring.ServiceAgentProxyFactory;
import org.wutong.tiangong.spring.TianGongRuntime;
import org.wutong.tiangong.spring.annotation.TianGongWrite;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * {@code @TianGongWrite} 处理器（Spring BeanPostProcessor）：把天工侧实例写入 Spring Bean。<br>
 * <br>
 * <h2>三类目标，三种时序</h2>
 * <ol>
 *     <li><b>{@code @ServiceAgent} 接口</b>：立即写服务代理（无状态，调用时才寻址）</li>
 *     <li><b>接口（非契约）</b>：就绪前写延迟 JDK 代理，就绪后回填真实例</li>
 *     <li><b>具体类</b>：就绪前写 ByteBuddy 占位（{@link DeferredInstancePlaceholder}），
 *         就绪后回填真实例（{@link SharedInstanceSpace#backfill()}）</li>
 * </ol>
 * 自举之后才创建的 Bean（懒加载等）走"直接解析"路径。<br>
 * <br>
 * <b>注入目标只允许是 Spring 管理的 Bean</b>（BPP 天然如此）；字段/setter 支持，构造器不支持。
 */
@Slf4j
public class TianGongWriteBeanPostProcessor implements BeanPostProcessor {

    private final SharedInstanceSpace space;
    private final Supplier<TianGongRuntime> runtime;
    private final Map<Class<?>, List<InjectPoint>> plans = new ConcurrentHashMap<>();
    private final Map<Class<?>, Object> serviceProxies = new ConcurrentHashMap<>();

    public TianGongWriteBeanPostProcessor(SharedInstanceSpace space, Supplier<TianGongRuntime> runtime) {
        this.space = space;
        this.runtime = runtime;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        List<InjectPoint> points = plans.computeIfAbsent(bean.getClass(), this::plan);
        if (points.isEmpty()) {
            return bean;
        }
        for (InjectPoint point : points) {
            if (ServiceAgentProxyFactory.isServiceAgentContract(point.type())) {
                point.writer().accept(bean, proxyFor(point.type()));
            } else if (space.isReady()) {
                // 自举后才创建的 Bean：直接解析（失败即抛，不留半初始化对象）
                point.writer().accept(bean,
                        space.resolveTianGongInstance(point.type(), point.name(), point.description()));
            } else {
                Object placeholder = placeholderFor(point);
                point.writer().accept(bean, placeholder);
                space.registerPendingWrite(new SharedInstanceSpace.PendingWrite(
                        bean, point.description(), point.type(), point.name(), point.writer(), placeholder));
            }
        }
        return bean;
    }

    // ==================== 规划 ====================

    private List<InjectPoint> plan(Class<?> beanClass) {
        List<InjectPoint> points = new ArrayList<>();

        for (Field field : beanClass.getDeclaredFields()) {
            TianGongWrite annotation = field.getAnnotation(TianGongWrite.class);
            if (annotation == null) {
                continue;
            }
            String description = beanClass.getSimpleName() + "." + field.getName();
            if (Modifier.isFinal(field.getModifiers())) {
                throw new SystemRuntimeException("@TianGongWrite 不支持 final 字段: " + description + "（去掉 final）");
            }
            field.setAccessible(true);
            points.add(new InjectPoint(field.getType(), resolveName(field.getAnnotation(Qualifier.class), annotation.value()),
                    description, (bean, value) -> {
                try {
                    field.set(bean, value);
                } catch (IllegalAccessException e) {
                    throw new SystemRuntimeException("@TianGongWrite 写入失败: " + description, e);
                }
            }));
        }

        for (Method method : beanClass.getDeclaredMethods()) {
            TianGongWrite annotation = method.getAnnotation(TianGongWrite.class);
            if (annotation == null) {
                continue;
            }
            String description = beanClass.getSimpleName() + "." + method.getName() + "()";
            if (method.getParameterCount() != 1) {
                throw new SystemRuntimeException(
                        "@TianGongWrite 方法必须恰好 1 个参数（setter 语义）: " + description
                                + "，实际 " + method.getParameterCount() + " 个");
            }
            method.setAccessible(true);
            points.add(new InjectPoint(method.getParameterTypes()[0],
                    resolveName(method.getAnnotation(Qualifier.class), annotation.value()),
                    description, (bean, value) -> {
                try {
                    method.invoke(bean, value);
                } catch (ReflectiveOperationException e) {
                    throw new SystemRuntimeException("@TianGongWrite 写入失败: " + description, e);
                }
            }));
        }
        return points;
    }

    private static String resolveName(Qualifier qualifier, String value) {
        if (qualifier != null && StringUtils.hasText(qualifier.value())) {
            return qualifier.value();
        }
        return value;
    }

    // ==================== 占位/代理 ====================

    private Object placeholderFor(InjectPoint point) {
        if (point.type().isInterface()) {
            return DeferredJdkProxy.create(point.type());
        }
        if (!DeferredInstancePlaceholder.isSupported(point.type())) {
            throw new SystemRuntimeException(
                    "@TianGongWrite [" + point.description() + "] 的依赖类型无法做启动期占位（"
                            + DeferredInstancePlaceholder.unsupportedReason(point.type())
                            + "）；请把依赖声明为接口");
        }
        List<String> finalMethods = DeferredInstancePlaceholder.finalMethods(point.type());
        if (!finalMethods.isEmpty()) {
            log.warn("@TianGongWrite [{}] 目标类 {} 含 final 方法 {}：占位无法拦截，"
                            + "就绪前调用它们会执行未初始化代码",
                    point.description(), point.type().getSimpleName(), finalMethods);
        }
        return DeferredInstancePlaceholder.create(point.type());
    }

    private Object proxyFor(Class<?> contract) {
        return serviceProxies.computeIfAbsent(contract,
                type -> ServiceAgentProxyFactory.create(type, runtime));
    }

    private record InjectPoint(
            Class<?> type,
            String name,
            String description,
            BiConsumer<Object, Object> writer
    ) {
    }
}
