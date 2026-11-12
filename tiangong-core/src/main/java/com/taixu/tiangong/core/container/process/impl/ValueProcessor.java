package com.taixu.tiangong.core.container.process.impl;

import lombok.extern.slf4j.Slf4j;
import com.taixu.tiangong.annotations.annotation.definition.BeanDefinition;
import com.taixu.tiangong.annotations.annotation.framework.inject.Value;
import com.taixu.tiangong.common.exception.ConfigKeyNoExistException;
import com.taixu.tiangong.common.exception.SystemRuntimeException;
import com.taixu.tiangong.common.util.ConvertUtil;
import com.taixu.tiangong.core.config.ConfigHolder;
import com.taixu.tiangong.core.container.process.AnnotationProcessor;

import java.lang.reflect.Field;

@Slf4j
public class ValueProcessor implements AnnotationProcessor {

    @Override
    public void process(Object instance, BeanDefinition definition) throws Exception {
        Class<?> clazz = definition.getBeanClass();   // 增强安全：用原始类查注解，避免 ByteBuddy 子类丢字段注解
        for (Field field : clazz.getDeclaredFields()) {
            field.setAccessible(true);
            Value value = field.getAnnotation(Value.class);
            if (value == null) continue;
            String key = value.value();
            String defaultValue = value.defaultValue();

            Object keyValue = lookup(key);
            if (keyValue == null) {
                if (defaultValue.isEmpty()) {
                    log.error("未在配置中找到 {} 键的值", key);
                    throw new ConfigKeyNoExistException("未在配置中找到 %s 键的值".formatted(key));
                }
                log.warn("未在配置中找到 {} 键的值，采用默认值：{}", key, defaultValue);
                assign(field, instance, defaultValue);
                continue;
            }
            log.debug("设置@Value({})的值为：{}", key, keyValue);
            assign(field, instance, keyValue);
        }
    }

    /**
     * 取值但不允许抛异常：{@code ConfigHolder.getValue} 在<b>键不存在</b>、以及
     * <b>路径中间节点不存在</b>两种情况下都会抛 —— 后者尤其致命：yml 里少了整段配置时，
     * 即使注解写了 defaultValue 也照样启动失败。这里统一退化成「没取到」，
     * 让 defaultValue 真正成为兜底而不是摆设。
     */
    private Object lookup(String key) {
        try {
            return ConfigHolder.getValue(key);
        } catch (SystemRuntimeException e) {
            return null;
        }
    }

    /**
     * 按字段声明类型做转换后再注入。<br>
     * 旧实现是 {@code field.set(instance, 原始值)}：YAML 解析出的 {@code 12} 是 Integer、
     * 注解 defaultValue 又是 String，同一个 {@code @Value} 两种来源两种类型，
     * 于是「把字段老老实实声明成 String」反而在启动期抛 {@code IllegalArgumentException}。
     * 现在两边都过一遍 {@link ConvertUtil}，字段声明成什么类型都能用。
     */
    private void assign(Field field, Object instance, Object raw) throws IllegalAccessException {
        try {
            field.set(instance, ConvertUtil.convert(raw, field.getType()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "@Value 注入失败：%s#%s（声明类型 %s）无法接受值 [%s]（实际类型 %s）；%s"
                            .formatted(field.getDeclaringClass().getSimpleName(),
                                    field.getName(),
                                    field.getType().getSimpleName(),
                                    raw,
                                    raw == null ? "null" : raw.getClass().getSimpleName(),
                                    e.getMessage()),
                    e);
        }
    }

}
