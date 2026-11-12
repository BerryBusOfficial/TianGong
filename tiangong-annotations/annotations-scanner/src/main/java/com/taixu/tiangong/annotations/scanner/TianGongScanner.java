package com.taixu.tiangong.annotations.scanner;

import com.taixu.tiangong.annotations.annotation.framework.Import;
import com.taixu.tiangong.annotations.annotation.framework.AutoConfiguration;
import com.taixu.tiangong.annotations.annotation.framework.ConditionalOnClass;
import com.taixu.tiangong.annotations.annotation.framework.enable.meta.FrameworkEnable;
import com.taixu.tiangong.annotations.annotation.framework.meta.MetaComponent;
import com.taixu.tiangong.annotations.annotation.framework.registrar.ComponentProcessor;
import com.taixu.tiangong.annotations.processor.factory.BeanDefinitionFactory;
import com.taixu.tiangong.annotations.processor.registry.BeanDefinitionRegistry;
import com.taixu.tiangong.annotations.processor.registry.RegistryAware;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassInfoList;
import io.github.classgraph.ScanResult;
import lombok.Getter;
import com.taixu.tiangong.annotations.annotation.definition.BeanDefinition;
import com.taixu.tiangong.common.exception.SystemRuntimeException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 框架组件扫描器。<br>
 * 扫描 classpath 下所有被 {@link MetaComponent} 元注解标记的注解，
 * 为每个被这些注解标记的类创建 {@link BeanDefinition}，
 * 并依次执行扩展处理器、组件处理器、注解处理器的管线。
 */
public class TianGongScanner {

    private final List<String> packages = new ArrayList<>(List.of("com.taixu.tiangong"));
    private final List<com.taixu.tiangong.annotations.processor.ComponentProcessor> processors = new ArrayList<>();
    @Getter
    private final BeanDefinitionRegistry beanDefinitionRegistry;
    private final BeanDefinitionFactory beanDefinitionFactory = BeanDefinitionFactory.create();
    @Getter
    private final List<EnableMetadata> enableMetadata = new ArrayList<>();
    @Getter
    private final List<Class<?>> autoConfigurations = new ArrayList<>();
    @Getter
    private final List<ImportMetadata> importMetadata = new ArrayList<>();
    private ClassGraph classGraph;

    private TianGongScanner() {
        this(new BeanDefinitionRegistry());
    }

    private TianGongScanner(BeanDefinitionRegistry registry) {
        this.beanDefinitionRegistry = registry;
        this.classGraph = buildClassGraph();
    }

    public static TianGongScanner load() {
        TianGongScanner scanner = new TianGongScanner();
        scanner.init();
        return scanner;
    }

    public static TianGongScanner load(Consumer<TianGongScanner> configurer) {
        TianGongScanner scanner = new TianGongScanner();
        configurer.accept(scanner);
        scanner.init();
        return scanner;
    }

    public static TianGongScanner load(BeanDefinitionRegistry registry, Consumer<TianGongScanner> configurer) {
        TianGongScanner scanner = new TianGongScanner(registry);
        configurer.accept(scanner);
        scanner.init();
        return scanner;
    }

    private ClassGraph buildClassGraph() {
        return new ClassGraph()
                .acceptPackages(packages.toArray(String[]::new))
                .enableAllInfo();
    }

    /**
     * 追加扫描包（去重）。框架包 org.wutong.tiangong 始终保留——框架注解定义与处理器依赖它。
     */
    public void acceptPackages(String... pkg) {
        for (String p : pkg) {
            if (!packages.contains(p)) {
                packages.add(p);
            }
        }
        this.classGraph = buildClassGraph();
    }

    public void addProcessor(com.taixu.tiangong.annotations.processor.ComponentProcessor processor) {
        processors.add(processor);
    }

    private void autoRegisterProcessors(ScanResult scan) {
        ClassInfoList componentProcessors = scan.getClassesWithAnnotation(ComponentProcessor.class.getName());
        for (ClassInfo info : componentProcessors) {
            try {
                Class<?> clazz = info.loadClass();
                com.taixu.tiangong.annotations.processor.ComponentProcessor processor = (com.taixu.tiangong.annotations.processor.ComponentProcessor) clazz.getConstructor().newInstance();
                if (processor instanceof RegistryAware aware) {
                    aware.setRegistry(beanDefinitionRegistry);
                }
                processors.add(processor);
            } catch (Exception e) {
                throw new SystemRuntimeException("自动注册 ComponentProcessor 失败: " + info.getName(), e);
            }
        }
    }

    private void init() {
        try (ScanResult scan = classGraph.scan()) {
            autoRegisterProcessors(scan);
            collectEnables(scan);
            collectAutoConfigurations(scan);
            collectImports(scan);

            ClassInfoList components = scan.getClassesWithAnnotation(MetaComponent.class.getName());
            Set<String> registeredClassNames = new HashSet<>();   // 按 beanClass 去重：同一类被多个注解（如 @Component + @Storage）命中时只注册一次
            for (ClassInfo component : components) {
                if (!component.isAnnotation()) {
                    continue;
                }
                ClassInfoList classes = scan.getClassesWithAnnotation(component.getName());
                for (ClassInfo classInfo : classes) {
                    if (classInfo.isAnnotation() || classInfo.isInterface()) {
                        continue;
                    }
                    if (!registeredClassNames.add(classInfo.getName())) {
                        continue;   // 已注册过 → 跳过（避免 @Component 和 @Storage 同时命中同一类导致重复注册）
                    }
                    Class<?> beanClass = classInfo.loadClass();
                    if (!conditionalMatches(beanClass)) {
                        continue;   // @ConditionalOnClass 不满足 → 跳过注册
                    }
                    BeanDefinition beanDefinition = beanDefinitionFactory.createBeanDefinition(classInfo, component);
                    for (com.taixu.tiangong.annotations.processor.ComponentProcessor processor : processors) {
                        if (matchComponentProcessor(processor, component)) {
                            processor.process(beanDefinition);
                        }
                    }
                    beanDefinitionRegistry.register(beanDefinition);
                }
            }
        }
    }

    /**
     * 收集 @Enable* 元数据：标 {@link FrameworkEnable} 的注解 → 标注了这些注解的用户类。
     * 只收集不装配，装配由容器创建后执行。
     */
    private void collectEnables(ScanResult scan) {
        ClassInfoList enableAnnotations = scan.getClassesWithAnnotation(FrameworkEnable.class.getName());
        for (ClassInfo enableAnn : enableAnnotations) {
            if (!enableAnn.isAnnotation()) {
                continue;
            }
            Class<?> annClass = enableAnn.loadClass();
            FrameworkEnable fe = annClass.getAnnotation(FrameworkEnable.class);
            if (fe == null) {
                continue;
            }
            ClassInfoList users = scan.getClassesWithAnnotation(enableAnn.getName());
            for (ClassInfo user : users) {
                if (user.isAnnotation() || user.isInterface()) {
                    continue;
                }
                enableMetadata.add(new EnableMetadata(
                        annClass, fe.value(), user.loadClass(), resolveEnabled(user.loadClass(), annClass)));
            }
        }
    }

    /**
     * 读取用户类上 @Enable* 注解的 value()。<br>
     * 直接标注 → 取注解实例的 value；组合标注（如 @TianGongApplicationStarter）→ 默认 true。
     */
    private boolean resolveEnabled(Class<?> userClass, Class<?> enableAnnotation) {
        try {
            @SuppressWarnings("unchecked")
            java.lang.annotation.Annotation annotation =
                    userClass.getAnnotation((Class<? extends java.lang.annotation.Annotation>) enableAnnotation);
            if (annotation == null) {
                return true;   // 组合标注（非直接标注）→ 组合处默认 true
            }
            return (boolean) annotation.annotationType().getMethod("value").invoke(annotation);
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 收集 @AutoConfiguration 配置类（是否注册由 @EnableAutoConfiguration 开关决定）
     */
    private void collectAutoConfigurations(ScanResult scan) {
        ClassInfoList configs = scan.getClassesWithAnnotation(AutoConfiguration.class.getName());
        for (ClassInfo info : configs) {
            if (info.isAnnotation() || info.isInterface()) {
                continue;
            }
            autoConfigurations.add(info.loadClass());
        }
    }

    /**
     * 收集 @Import 导入声明：用户类 → 要导入注册的类列表
     */
    private void collectImports(ScanResult scan) {
        ClassInfoList imports = scan.getClassesWithAnnotation(Import.class.getName());
        for (ClassInfo info : imports) {
            if (info.isAnnotation() || info.isInterface()) {
                continue;
            }
            Class<?> userClass = info.loadClass();
            Import ann = userClass.getAnnotation(Import.class);
            if (ann == null || ann.value().length == 0) {
                continue;
            }
            importMetadata.add(new ImportMetadata(userClass, ann.value()));
        }
    }

    private boolean matchComponentProcessor(com.taixu.tiangong.annotations.processor.ComponentProcessor processor, ClassInfo component) {
        ComponentProcessor ann = processor.getClass().getAnnotation(ComponentProcessor.class);
        return ann != null && component.getName().equals(ann.processOn().getName());
    }

    /**
     * 条件装配检查：类标 {@code @ConditionalOnClass} 时，value 中所有类名必须存在（Class.forName）。
     * 组件扫描注册 BeanDefinition 时应用。
     */
    private boolean conditionalMatches(Class<?> clazz) {
        ConditionalOnClass condition = clazz.getAnnotation(ConditionalOnClass.class);
        if (condition == null) {
            return true;
        }
        for (String className : condition.value()) {
            try {
                Class.forName(className, false, clazz.getClassLoader());
            } catch (ClassNotFoundException e) {
                return false;
            }
        }
        return true;
    }
}
