package com.taixu.tiangong.annotations.processor.util;

import com.taixu.tiangong.common.exception.SystemRuntimeException;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

public class ReflectUtil {

    @SuppressWarnings("unchecked")
    public static <T> Class<T> getInterfaceType(
            Class<?> implClass,
            Class<?> interfaceType) {

        for (Type type : implClass.getGenericInterfaces()) {
            if (!(type instanceof ParameterizedType p)) {
                continue;
            }
            if (!(p.getRawType() instanceof Class<?> raw)) {
                continue;
            }
            if (!raw.equals(interfaceType)) {
                continue;
            }

            Type actual = p.getActualTypeArguments()[0];

            if (actual instanceof Class<?> clazz) {
                return (Class<T>) clazz;
            }
        }
        throw new SystemRuntimeException(implClass + " does not implement " + interfaceType);
    }

}
