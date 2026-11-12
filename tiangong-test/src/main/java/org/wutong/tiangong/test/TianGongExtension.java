package org.wutong.tiangong.test;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;
import org.wutong.tiangong.annotations.annotation.framework.inject.Inject;
import org.wutong.tiangong.common.exception.SystemRuntimeException;
import org.wutong.tiangong.common.resource.ResourceLoader;
import org.wutong.tiangong.core.config.ConfigLoader;
import org.wutong.tiangong.core.container.Container;
import org.wutong.tiangong.core.container.context.framework.ApplicationFrameworkContext;
import org.wutong.tiangong.core.function.FunctionRegistry;
import org.wutong.tiangong.core.llm.DefaultChatRequestBuilder;
import org.wutong.tiangong.core.llm.DefaultEmbeddingRequestBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

/**
 * JUnit 5 扩展（配合 {@link TianGongApplicationTestStarter} 使用）。<br>
 * 标了 {@link TianGongApplicationTestStarter} 的测试类：自动加载配置、创建应用上下文，
 * 并按 {@link Inject} 注入依赖。
 */
@Slf4j
public class TianGongExtension implements TestInstancePostProcessor {

    @Override
    public void postProcessTestInstance(Object testInstance, ExtensionContext context) {
        if (!testInstance.getClass().isAnnotationPresent(TianGongApplicationTestStarter.class)) {
            return;
        }
        ResourceLoader.load(testInstance.getClass());
        try {
            ConfigLoader.load();
        } catch (Exception e) {
            log.warn("测试配置加载失败，使用空配置: {}", e.getMessage());
        }
        ApplicationFrameworkContext appContext = ApplicationFrameworkContext.create();
        injectFields(testInstance, appContext);
    }

    private void injectFields(Object test, ApplicationFrameworkContext ctx) {
        for (Field field : test.getClass().getDeclaredFields()) {
            if (!field.isAnnotationPresent(Inject.class)) {
                continue;
            }
            Object dependency = resolveDependency(field, ctx);
            if (dependency != null) {
                field.setAccessible(true);
                try {
                    field.set(test, dependency);
                } catch (Exception e) {
                    throw new SystemRuntimeException("注入失败: " + field.getName(), e);
                }
            }
        }
    }

    private Object resolveDependency(Field field, ApplicationFrameworkContext ctx) {
        Class<?> type = field.getType();
        if (type == ApplicationFrameworkContext.class) return ctx;
        if (type == Container.class || type == ctx.getContainer().getClass()) return ctx.getContainer();
        if (type == FunctionRegistry.class) return ctx.getFunctionRegistry();
        if (type == org.wutong.tiangong.core.llm.LlmRegistry.class) return ctx.getLlmRegistry();

        Inject inject = field.getAnnotation(Inject.class);
        if (inject != null) {
            return switch (inject.source()) {
                case BEAN -> resolveBeanByQualifier(field, inject, ctx);
                case LLM -> ctx.getLlmRegistry().get(inject.value()).orElse(null);
                case REQUEST -> switch (inject.request()) {
                    case CHAT -> new DefaultChatRequestBuilder(
                            ctx.getLlmRegistry(), inject.value(), ctx.getFunctionRegistry());
                    case EMBEDDING -> new DefaultEmbeddingRequestBuilder(ctx.getLlmRegistry(), inject.value());
                };
                case RESOURCE -> readResource(inject.value());
            };
        }
        return ctx.getContainer().getBean(type);
    }

    /**
     * BEAN 注入：@Qualifier 优先按名解析，否则按类型。
     */
    private Object resolveBeanByQualifier(Field field, Inject inject, ApplicationFrameworkContext ctx) {
        org.wutong.tiangong.annotations.annotation.framework.inject.Qualifier qualifier =
                field.getAnnotation(org.wutong.tiangong.annotations.annotation.framework.inject.Qualifier.class);
        if (qualifier != null) {
            Object bean = ctx.getContainer().getBean(qualifier.value());
            if (bean != null) return bean;
        }
        String name = inject.value().isBlank() ? null : inject.value();
        if (name != null) {
            Object bean = ctx.getContainer().getBean(name);
            if (bean != null) return bean;
        }
        return ctx.getContainer().getBean(field.getType());
    }

    private Object readResource(String path) {
        try (InputStream in = ResourceLoader.getResourceAsStream(path)) {
            if (in == null) {
                throw new SystemRuntimeException("资源不存在: " + path, new IllegalArgumentException());
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SystemRuntimeException("资源读取失败: " + path, e);
        }
    }
}
