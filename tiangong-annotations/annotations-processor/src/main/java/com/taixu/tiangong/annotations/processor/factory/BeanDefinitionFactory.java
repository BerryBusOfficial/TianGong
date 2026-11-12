package com.taixu.tiangong.annotations.processor.factory;

import io.github.classgraph.AnnotationEnumValue;
import io.github.classgraph.AnnotationInfo;
import io.github.classgraph.ClassInfo;
import com.taixu.tiangong.annotations.annotation.definition.BeanDefinition;
import com.taixu.tiangong.annotations.annotation.definition.Lifecycle;
import com.taixu.tiangong.annotations.annotation.framework.LazyInit;
import com.taixu.tiangong.annotations.annotation.framework.Scope;
import com.taixu.tiangong.common.util.JsonUtil;

public class BeanDefinitionFactory {


    public static BeanDefinitionFactory create() {
        return new BeanDefinitionFactory();
    }

    public BeanDefinition createBeanDefinition(ClassInfo classInfo, ClassInfo component) {
        AnnotationInfo annotation = classInfo.getAnnotationInfo(component.getName());

        String beanName = resolveBeanParam(annotation, "name", String.class);
        if (beanName == null) {
            beanName = resolveBeanParam(annotation, "value", String.class);
        }
        Lifecycle lifecycle = resolveScope(classInfo);
        boolean lazyInit = resolveLazyInit(classInfo);
        Class<?> beanClass = classInfo.loadClass();
        if (beanName == null) {
            beanName = beanClass.getSimpleName();
        }

        return BeanDefinition.builder()
                .beanName(beanName.isBlank() ? beanClass.getSimpleName() : beanName)
                .beanClass(beanClass)
                .lifecycle(lifecycle)
                .lazyInit(lazyInit)
                .build();
    }

    private Lifecycle resolveScope(ClassInfo classInfo) {
        AnnotationInfo ann = classInfo.getAnnotationInfo(Scope.class.getName());
        if (ann == null) return Lifecycle.SINGLETON;
        Lifecycle lifecycle = resolveBeanParam(ann, "value", Lifecycle.class);
        return lifecycle != null ? lifecycle : Lifecycle.SINGLETON;
    }

    private boolean resolveLazyInit(ClassInfo classInfo) {
        AnnotationInfo ann = classInfo.getAnnotationInfo(LazyInit.class.getName());
        if (ann == null) return false;                     // 默认
        return Boolean.TRUE.equals(resolveBeanParam(ann, "value", Boolean.class));
    }

    private <T> T resolveBeanParam(AnnotationInfo annotation, String key, Class<T> beanClass) {
        var paramVal = annotation.getParameterValues().get(key);
        if (paramVal == null) {
            return null;
        }
        Object value = paramVal.getValue();
        if (value == null) {
            return null;
        }
        if (beanClass.isInstance(value)) {
            return beanClass.cast(value);
        }
        if (beanClass.isEnum()) {
            String name = ((AnnotationEnumValue) value).getName();
            if (name.contains(".")) {
                name = name.substring(name.lastIndexOf('.') + 1);
            }
            return beanClass.cast(
                    Enum.valueOf(beanClass.asSubclass(Enum.class), name)
            );
        }
        return JsonUtil.convertToBean(value, beanClass);
    }

}
