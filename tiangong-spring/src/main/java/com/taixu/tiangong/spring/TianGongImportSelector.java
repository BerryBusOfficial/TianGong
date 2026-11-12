package com.taixu.tiangong.spring;

import org.springframework.context.annotation.ImportSelector;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.util.ClassUtils;
import com.taixu.tiangong.spring.annotation.EnableTianGong;
import com.taixu.tiangong.spring.annotation.MultiProcess;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code @EnableTianGong} 的装配入口：<br>
 * 记录启动类与额外扫描包（{@link TianGongAppInfo}），再导入 {@link TianGongConfiguration}。
 */
public class TianGongImportSelector implements ImportSelector {

    @Override
    public String[] selectImports(AnnotationMetadata importingClassMetadata) {
        String className = importingClassMetadata.getClassName();

        // 分进程模式与同进程自举互斥：远程传输尚未实现（见 @MultiProcess 注释）
        if (importingClassMetadata.hasAnnotation(MultiProcess.class.getName())) {
            throw new IllegalStateException(
                    "@MultiProcess（分进程部署）与 @EnableTianGong（同进程自举）互斥："
                            + "分进程的远程传输尚未实现，当前仅支持同进程集成。\n"
                            + "请移除 @MultiProcess 使用 @EnableTianGong；"
                            + "或等远程寻址落地后再启用分进程模式。启动类: " + className);
        }

        List<String> packages = new ArrayList<>();

        AnnotationAttributes attrs = AnnotationAttributes.fromMap(
                importingClassMetadata.getAnnotationAttributes(EnableTianGong.class.getName()));
        if (attrs != null) {
            String[] declared = attrs.getStringArray("packages");
            if (declared != null) {
                for (String p : declared) {
                    if (p != null && !p.isBlank()) {
                        packages.add(p.trim());
                    }
                }
            }
        }

        Class<?> appClass = resolveAppClass(className);
        TianGongAppInfo.record(appClass, packages);
        return new String[]{TianGongConfiguration.class.getName()};
    }

    private Class<?> resolveAppClass(String className) {
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        try {
            return ClassUtils.forName(className, contextLoader != null
                    ? contextLoader : TianGongImportSelector.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "无法加载 @EnableTianGong 标注的启动类: " + className, e);
        }
    }
}
