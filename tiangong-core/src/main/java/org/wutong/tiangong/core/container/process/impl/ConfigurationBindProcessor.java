package org.wutong.tiangong.core.container.process.impl;

import lombok.extern.slf4j.Slf4j;
import org.wutong.tiangong.annotations.annotation.definition.BeanDefinition;
import org.wutong.tiangong.annotations.annotation.framework.Configuration;
import org.wutong.tiangong.common.util.JsonUtil;
import org.wutong.tiangong.core.config.ConfigHolder;
import org.wutong.tiangong.core.container.process.AnnotationProcessor;

import java.lang.reflect.Field;

/**
 * @AgentConfiguration(prefix) 配置绑定处理器：prefix + 字段名(SNAKE_CASE) 批量绑定。<br>
 * 缺失配置键 → 字段置 null + 警告日志（不抛异常，与 @Value 显式键区分）。
 */
@Slf4j
public class ConfigurationBindProcessor implements AnnotationProcessor {

    @Override
    public void process(Object instance, BeanDefinition definition) throws Exception {
        Class<?> clazz = definition.getBeanClass();
        Configuration ann = clazz.getAnnotation(Configuration.class);
        if (ann == null) {
            return;
        }
        String prefix = ann.prefix();
        for (Field field : clazz.getDeclaredFields()) {
            String key = prefix + "." + toSnakeCase(field.getName());
            Object value;
            try {
                value = ConfigHolder.getValue(key);
            } catch (Exception e) {
                value = null;
            }
            if (value == null) {
                log.warn("配置绑定缺失键: {}（{} 字段置 null）", key, clazz.getSimpleName() + "." + field.getName());
                field.setAccessible(true);
                field.set(instance, null);
                continue;
            }
            field.setAccessible(true);
            field.set(instance, convert(value, field.getType()));
        }
    }

    private Object convert(Object value, Class<?> type) {
        if (type.isInstance(value)) {
            return value;
        }
        return JsonUtil.convertToBean(value, type);
    }

    private String toSnakeCase(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (Character.isUpperCase(c)) {
                sb.append('_').append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
