package com.taixu.tiangong.core.scheduled;

import lombok.extern.slf4j.Slf4j;
import com.taixu.tiangong.annotations.annotation.definition.BeanDefinition;
import com.taixu.tiangong.annotations.annotation.framework.Scheduled;
import com.taixu.tiangong.core.container.process.AnnotationProcessor;

import java.lang.reflect.Method;

/**
 * @Scheduled 注解处理器：扫描 Bean 中的 @Scheduled 方法并注册到 ScheduledTaskRegistry。<br>
 * <br>
 * 仅在 @EnableScheduling 开启时由 ApplicationFrameworkContext 装配。
 */
@Slf4j
public class ScheduledAnnotationProcessor implements AnnotationProcessor {

    private final ScheduledTaskRegistry registry;

    public ScheduledAnnotationProcessor(ScheduledTaskRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void process(Object instance, BeanDefinition definition) throws Exception {
        Class<?> clazz = definition.getBeanClass();
        for (Method method : clazz.getDeclaredMethods()) {
            Scheduled ann = method.getAnnotation(Scheduled.class);
            if (ann == null) {
                continue;
            }
            validateMethod(method);
            ScheduledTaskDefinition task = ScheduledTaskDefinition.of(
                    definition.getBeanName(), instance, method, ann);
            registry.register(task);
            log.debug("注册定时任务: {}", task);
        }
    }

    private void validateMethod(Method method) {
        if (method.getParameterCount() != 0) {
            throw new IllegalStateException(
                    "@Scheduled 方法不能有参数: " + method.getName());
        }
    }
}
