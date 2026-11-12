package com.taixu.tiangong.core.container.process.impl;

import lombok.RequiredArgsConstructor;
import com.taixu.tiangong.annotations.annotation.definition.BeanDefinition;
import com.taixu.tiangong.annotations.annotation.framework.inject.Inject;
import com.taixu.tiangong.annotations.annotation.framework.inject.Qualifier;
import com.taixu.tiangong.common.exception.ResourceNotFoundException;
import com.taixu.tiangong.common.exception.SystemRuntimeException;
import com.taixu.tiangong.common.resource.ResourceLoader;
import com.taixu.tiangong.core.container.context.ContextHolder;
import com.taixu.tiangong.core.container.process.AnnotationProcessor;
import com.taixu.tiangong.core.function.FunctionRegistry;
import com.taixu.tiangong.core.llm.DefaultChatRequestBuilder;
import com.taixu.tiangong.core.llm.DefaultEmbeddingRequestBuilder;
import com.taixu.tiangong.core.llm.LlmRegistry;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 统一注入处理器：按 {@link Inject#source()} 分派 BEAN / LLM / REQUEST / RESOURCE 注入。
 */
@RequiredArgsConstructor
public class InjectProcessor implements AnnotationProcessor {

    private final LlmRegistry llmRegistry;
    private final FunctionRegistry functionRegistry;

    @Override
    public void process(Object instance, BeanDefinition definition) throws Exception {
        Class<?> clazz = definition.getBeanClass();   // 增强安全
        for (Field field : clazz.getDeclaredFields()) {
            Inject inject = field.getAnnotation(Inject.class);
            if (inject == null) continue;

            Object dependency = switch (inject.source()) {
                case BEAN -> resolveBean(field, inject);
                case LLM -> resolveLlm(field, inject);
                case REQUEST -> resolveRequest(field, inject);
                case RESOURCE -> resolveResource(field, inject);
            };
            field.setAccessible(true);
            field.set(instance, dependency);
        }
    }

    // ============ BEAN ============

    private Object resolveBean(Field field, Inject inject) {
        Qualifier qualifier = field.getAnnotation(Qualifier.class);
        String beanName = qualifier != null
                ? qualifier.value()                                   // @Qualifier 优先
                : (inject.value().isBlank() ? field.getType().getSimpleName() : inject.value());
        Object dependency = ContextHolder.getApplication().getContainer().getBean(beanName);
        if (dependency == null) {
            dependency = ContextHolder.getApplication().getContainer().getBean(field.getType());   // 按类型回退
        }
        if (dependency == null) {
            throw new SystemRuntimeException(
                    "未找到 @Inject 依赖: [%s] 注入到 [%s.%s]".formatted(
                            beanName,
                            field.getDeclaringClass().getSimpleName(),
                            field.getName()
                    ),
                    new IllegalStateException()
            );
        }
        return dependency;
    }

    // ============ LLM ============

    private Object resolveLlm(Field field, Inject inject) {
        return llmRegistry.get(inject.value())
                .orElseThrow(() -> new SystemRuntimeException(
                        "未找到 LLM 客户端: " + inject.value(), new IllegalArgumentException()));
    }

    // ============ REQUEST ============

    private Object resolveRequest(Field field, Inject inject) {
        llmRegistry.get(inject.value())
                .orElseThrow(() -> new SystemRuntimeException(
                        "未找到 LLM 客户端: " + inject.value(), new IllegalArgumentException()));
        return switch (inject.request()) {
            case CHAT -> new DefaultChatRequestBuilder(llmRegistry, inject.value(), functionRegistry);
            case EMBEDDING -> new DefaultEmbeddingRequestBuilder(llmRegistry, inject.value());
        };
    }

    // ============ RESOURCE ============

    private Object resolveResource(Field field, Inject inject) throws IOException {
        Class<?> type = field.getType();
        String path = inject.value();
        if (type == String.class) {
            try (InputStream in = requireStream(path)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        if (type == File.class) {
            URL url = requireUrl(path);
            try {
                return new File(url.toURI());
            } catch (URISyntaxException e) {
                throw new SystemRuntimeException(
                        "资源无法转换为 File（可能位于 jar 包内，仅支持 String/URL/URI/InputStream）: " + path, e);
            }
        }
        if (type == URL.class) {
            return requireUrl(path);
        }
        if (type == URI.class) {
            try {
                return requireUrl(path).toURI();
            } catch (URISyntaxException e) {
                throw new SystemRuntimeException("非法URI路径", e);
            }
        }
        if (type == InputStream.class) {
            return requireStream(path);
        }
        throw new SystemRuntimeException("不支持的 @Inject(source=RESOURCE) 注入类型: " + type.getName());
    }

    private URL requireUrl(String path) {
        URL url = ResourceLoader.getResource(path);
        if (url == null) {
            throw new ResourceNotFoundException("资源不存在: " + path);
        }
        return url;
    }

    private InputStream requireStream(String path) {
        InputStream in = ResourceLoader.getResourceAsStream(path);
        if (in == null) {
            throw new ResourceNotFoundException("资源不存在: " + path);
        }
        return in;
    }
}
