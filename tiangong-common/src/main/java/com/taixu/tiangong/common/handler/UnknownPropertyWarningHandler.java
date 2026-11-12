package com.taixu.tiangong.common.handler;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

/**
 * 未知字段警告处理器：POJO 绑定时遇到未知字段，
 * 输出 warn 日志并跳过，不再静默丢弃或抛异常。
 */
@Slf4j
public class UnknownPropertyWarningHandler extends DeserializationProblemHandler {

    @Override
    public boolean handleUnknownProperty(DeserializationContext ctxt, JsonParser p,
                                         JsonDeserializer<?> deserializer,
                                         Object beanOrClass, String propertyName)
            throws IOException {
        log.warn("未知字段被忽略: [{}] 绑定目标: [{}]",
                propertyName,
                beanOrClass != null ? beanOrClass.getClass().getSimpleName() : "?");
        p.skipChildren();   // 跳过字段值，保证解析状态正确
        return true;        // 已处理，不抛异常
    }

}
