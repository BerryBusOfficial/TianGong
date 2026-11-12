package org.wutong.tiangong.spring.bridge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.util.StringUtils;
import org.wutong.tiangong.annotations.annotation.definition.BeanDefinition;
import org.wutong.tiangong.annotations.annotation.framework.inject.Qualifier;
import org.wutong.tiangong.common.exception.SystemRuntimeException;
import org.wutong.tiangong.core.container.process.AnnotationProcessor;
import org.wutong.tiangong.spring.annotation.SpringWrite;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * {@code @SpringWrite} 处理器（天工 AnnotationProcessor）：把 Spring 侧实例写入天工 Bean。<br>
 * <br>
 * <b>时序天然成立</b>：天工自举发生在 Spring 单例就绪之后，所以这里直接解析真实例
 * —— 不需要占位/回填（这正是"Spring 先、天工后"的顺序红利）。<br>
 * <br>
 * <b>注入目标只允许是天工管理的 Bean</b>；字段/setter 支持，构造器不支持
 * （跨容器构造时机无解，请用字段/setter 或 Spring 原生注入）。<br>
 * <br>
 * 解析规则对齐 Spring：{@code @SpringWrite("name")}（字段上的天工 {@code @Qualifier} 优先）
 * 按名；否则按类型 —— 多候选无 {@code @Primary} 时由 Spring 抛 {@code NoUniqueBeanDefinitionException}，
 * 这里转成带注入点上下文的明确报错。
 */
@Slf4j
public class SpringWriteProcessor implements AnnotationProcessor {

    private final ApplicationContext springContext;

    public SpringWriteProcessor(ApplicationContext springContext) {
        this.springContext = springContext;
    }

    @Override
    public void process(Object instance, BeanDefinition definition) {
        Class<?> beanClass = definition.getBeanClass();

        for (Field field : beanClass.getDeclaredFields()) {
            SpringWrite annotation = field.getAnnotation(SpringWrite.class);
            if (annotation == null) {
                continue;
            }
            String description = beanClass.getSimpleName() + "." + field.getName();
            if (Modifier.isFinal(field.getModifiers())) {
                throw new SystemRuntimeException("@SpringWrite 不支持 final 字段: " + description + "（去掉 final）");
            }
            Object dependency = resolve(field.getType(),
                    resolveName(field.getAnnotation(Qualifier.class), annotation.value()), description);
            field.setAccessible(true);
            try {
                field.set(instance, dependency);
            } catch (IllegalAccessException e) {
                throw new SystemRuntimeException("@SpringWrite 写入失败: " + description, e);
            }
        }

        for (Method method : beanClass.getDeclaredMethods()) {
            SpringWrite annotation = method.getAnnotation(SpringWrite.class);
            if (annotation == null) {
                continue;
            }
            String description = beanClass.getSimpleName() + "." + method.getName() + "()";
            if (method.getParameterCount() != 1) {
                throw new SystemRuntimeException(
                        "@SpringWrite 方法必须恰好 1 个参数（setter 语义）: " + description
                                + "，实际 " + method.getParameterCount() + " 个");
            }
            Object dependency = resolve(method.getParameterTypes()[0],
                    resolveName(method.getAnnotation(Qualifier.class), annotation.value()), description);
            method.setAccessible(true);
            try {
                method.invoke(instance, dependency);
            } catch (ReflectiveOperationException e) {
                throw new SystemRuntimeException("@SpringWrite 写入失败: " + description, e);
            }
        }
    }

    // ==================== 内部 ====================

    private Object resolve(Class<?> type, String name, String description) {
        try {
            if (StringUtils.hasText(name)) {
                Object bean = springContext.getBean(name);
                if (!type.isInstance(bean)) {
                    throw new SystemRuntimeException(
                            "@SpringWrite 依赖类型不符: " + description + "，名字 [" + name + "] 是 "
                                    + bean.getClass().getName() + "，不是 " + type.getName());
                }
                return bean;
            }
            return springContext.getBean(type);
        } catch (NoUniqueBeanDefinitionException e) {
            throw new SystemRuntimeException(
                    "@SpringWrite 依赖存在多个候选，必须用 @SpringWrite(\"beanName\") 指定: "
                            + description + "；" + e.getMessage(), e);
        } catch (NoSuchBeanDefinitionException e) {
            throw new SystemRuntimeException(
                    "@SpringWrite 依赖未找到: " + description + "（类型 " + type.getName() + "）；"
                            + "请确认该 Spring Bean 已注册", e);
        }
    }

    private static String resolveName(Qualifier qualifier, String value) {
        if (qualifier != null && StringUtils.hasText(qualifier.value())) {
            return qualifier.value();
        }
        return value;
    }
}
