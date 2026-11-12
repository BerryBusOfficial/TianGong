package org.wutong.tiangong.core.container;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import org.wutong.tiangong.annotations.annotation.definition.BeanDefinition;
import org.wutong.tiangong.annotations.annotation.framework.Primary;
import org.wutong.tiangong.annotations.annotation.framework.component.Async;
import org.wutong.tiangong.annotations.annotation.framework.exception.ListenException;
import org.wutong.tiangong.annotations.annotation.framework.inject.Inject;
import org.wutong.tiangong.annotations.annotation.framework.inject.Qualifier;
import org.wutong.tiangong.annotations.annotation.framework.trace.Trace;
import org.wutong.tiangong.annotations.processor.registry.BeanDefinitionRegistry;
import org.wutong.tiangong.bytebuddy.AgentEnhancer;
import org.wutong.tiangong.common.exception.SystemRuntimeException;
import org.wutong.tiangong.core.container.context.ContextHolder;
import org.wutong.tiangong.core.container.process.AnnotationProcessor;
import org.wutong.tiangong.core.exception.ExceptionListenerInvocationHandler;
import org.wutong.tiangong.core.thread.AsyncInvocationHandler;
import org.wutong.tiangong.core.thread.TaskExecutorRegistry;
import org.wutong.tiangong.core.trace.TraceAnalyzer;
import org.wutong.tiangong.core.trace.TraceHandlerRegistry;
import org.wutong.tiangong.core.trace.TraceInvocationHandler;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.wutong.tiangong.annotations.annotation.definition.Lifecycle.SINGLETON;

/**
 * 框架级容器实现，按 {@link BeanDefinition} 和生命周期策略管理 Bean 实例。<br>
 * 创建实例时自动处理存在 {@link AnnotationProcessor} 处理的注入。
 */
@Slf4j
public final class FrameworkContainer implements Container {

    private static final ElementMatcher.Junction<MethodDescription> LISTEN_MATCHER =
            ElementMatchers.<MethodDescription>isAnnotatedWith(ListenException.class)
                    .or(method -> method.getDeclaringType().asErasure()
                            .getDeclaredAnnotations().isAnnotationPresent(ListenException.class))
                    .and(ElementMatchers.not(ElementMatchers.isDeclaredBy(Object.class)));
    private final BeanDefinitionRegistry registry;
    private final Map<String, Object> singletonCache = new ConcurrentHashMap<>();
    private final List<AnnotationProcessor> processors = new CopyOnWriteArrayList<>();
    /**
     * 正在创建中的 Bean 名：<b>必须按线程隔离</b>。<br>
     * <br>
     * 循环依赖只可能发生在<b>同一个线程的建栈</b>里（A 的注入触发 B，B 又回到 A），
     * 所以判定集合天然属于单线程。早先它是全局共享集合，于是「两个线程各自创建同一个
     * 原型 Bean」会被误判成循环依赖：多 START 工作流同时启动时，第二条链在
     * {@code WorkflowScheduler.doStart} 里取上下文直接抛
     * 「检测到循环依赖: PetContext」而启动失败（只能靠触发器的下一轮轮询碰运气重试）。<br>
     * 改为 ThreadLocal 后，各自线程的建栈互不可见，误判消失。
     */
    private final ThreadLocal<Set<String>> creating = ThreadLocal.withInitial(HashSet::new);
    /**
     * 单例创建锁。<b>只保护「创建」这一段，不保护读</b> —— 命中缓存的 getBean 完全无锁。
     */
    private final Object singletonLock = new Object();
    @Setter
    private TaskExecutorRegistry taskExecutorRegistry;
    /**
     * &#064;EnableAgentAsync  开启后，含 @Async 方法的 Bean 才会被增强
     */
    @Setter
    private boolean asyncEnabled;
    /**
     * &#064;EnableTrace  开启后，含 @Trace 的类/方法/字段才会被跟踪
     */
    @Setter
    @Getter
    private boolean traceEnabled;
    @Setter
    private TraceHandlerRegistry traceHandlerRegistry;
    /**
     * &#064;EnableAutoConfiguration  开启后，@AutoConfiguration 配置类才被注册为 Bean
     */
    @Setter
    @Getter
    private boolean autoConfigurationEnabled;
    /**
     * &#064;EnableExceptionListener  开启后，含 @ListenException 的方法/类才会被增强
     */
    @Setter
    @Getter
    private boolean exceptionListenerEnabled;

    private FrameworkContainer(BeanDefinitionRegistry registry) {
        this.registry = registry;
    }

    public static FrameworkContainer create(BeanDefinitionRegistry registry,
                                            Consumer<? super FrameworkContainer> configurator) {
        FrameworkContainer container = new FrameworkContainer(registry);
        configurator.accept(container);
        return container;
    }

    public void addAnnotationProcessor(AnnotationProcessor processor) {
        processors.add(processor);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getBean(String name) {
        BeanDefinition def = registry.get(name).orElse(null);
        if (def == null) {
            return null;
        }
        return switch (def.getLifecycle() == null ? SINGLETON : def.getLifecycle()) {
            case SINGLETON -> (T) singleton(name, def);
            case PROTOTYPE -> (T) createBean(def);
        };
    }

    @Override
    public <T> T getBean(String name, Class<T> type) {
        BeanDefinition def = registry.get(name).orElse(null);
        if (def == null) {
            return null;
        }
        return switch (def.getLifecycle() == null ? SINGLETON : def.getLifecycle()) {
            case SINGLETON -> type.cast(singleton(name, def));
            case PROTOTYPE -> type.cast(createBean(def));
        };
    }

    /**
     * 取单例，缺失则创建。<br>
     * <b>为什么不用 {@code computeIfAbsent}</b>：旧的写法是
     * {@code singletonCache.computeIfAbsent(name, k -> createBean(def))}，而 createBean 内部
     * 解析 {@code @Inject} 时会<b>再次</b>对本 map 调 computeIfAbsent（创建被依赖的 Bean）。
     * JDK 的 ConcurrentHashMap 不允许在 computeIfAbsent 的映射函数里递归更新同一个 map，
     * 检测到就抛 {@code IllegalStateException: Recursive update}。<br>
     * 触发条件是「嵌套两层 Bean 且两个 name 的哈希撞进同一个 bin」——所以它<b>偶发</b>：
     * 同样的代码换个 Bean 名可能就好，启动期直接崩，且不读源码根本定位不到。<br>
     * 改成「无锁快路径 + 加锁双检查」：命中缓存完全无锁，未命中时串行创建。
     * 真正的循环依赖由 {@link #creating}（ThreadLocal）给出可读报错，而不是 JDK 那句。
     */
    private Object singleton(String name, BeanDefinition def) {
        Object bean = singletonCache.get(name);
        if (bean != null) {
            return bean;
        }
        synchronized (singletonLock) {
            bean = singletonCache.get(name);
            if (bean != null) {
                return bean;
            }
            bean = createBean(def);
            singletonCache.put(name, bean);
            return bean;
        }
    }

    @Override
    public <T> T getBean(Class<T> type) {
        // 1. 收集所有 @Primary 候选，按 value 升序，取最小（优先级最高）
        BeanDefinition best = null;
        int bestOrder = Integer.MAX_VALUE;
        for (BeanDefinition def : registry) {
            if (!type.isAssignableFrom(def.getBeanClass())) {
                continue;
            }
            Primary primary = def.getBeanClass().getAnnotation(Primary.class);
            if (primary == null) {
                continue;
            }
            if (primary.value() < bestOrder) {
                bestOrder = primary.value();
                best = def;
            }
        }
        if (best != null) {
            return getBean(best.getBeanName());
        }

        // 2. 无 @Primary → 取第一个（现状）
        for (BeanDefinition def : registry) {
            if (type.isAssignableFrom(def.getBeanClass())) {
                return getBean(def.getBeanName());
            }
        }
        return null;
    }

    @Override
    public void register(String name, Object instance) {
        singletonCache.put(name, instance);
    }

    /**
     * 列出注册表中所有可赋值给给定类型的 Bean 定义。<br>
     * <br>
     * <b>为什么需要它</b>：{@link #getBean(Class)} 是"取第一个候选"的宽松语义
     * （无 @Primary 时第一个命中即返回）；而跨容器桥（如 {@code @TianGongWrite}）
     * 要求 Spring 风格解析 —— <b>多候选且无 @Primary 时必须报错</b>，不能静默选错实例。
     * 把候选集暴露出来，由调用方按自己的语义裁决。
     */
    public List<BeanDefinition> candidatesOf(Class<?> type) {
        List<BeanDefinition> candidates = new ArrayList<>();
        for (BeanDefinition def : registry) {
            if (type.isAssignableFrom(def.getBeanClass())) {
                candidates.add(def);
            }
        }
        return candidates;
    }

    @SuppressWarnings("unchecked")
    private <T> T createBean(BeanDefinition def) {
        Set<String> stack = creating.get();
        if (!stack.add(def.getBeanName())) {
            throw new SystemRuntimeException("检测到循环依赖: " + def.getBeanName(),
                    new IllegalStateException());
        }
        try {
            Object instance = instantiate(def);
            for (AnnotationProcessor processor : processors) {
                log.debug("创建Bean: {}, 加载注解处理器: {}", def.getBeanName(), processor.getClass().getSimpleName());
                processor.process(instance, def);
            }
            return (T) instance;
        } catch (Exception e) {
            throw new SystemRuntimeException("创建 Bean 失败: " + def.getBeanName(), e);
        } finally {
            stack.remove(def.getBeanName());
            // 建栈空了就摘掉 ThreadLocal：虚拟线程生命周期长且数量多，留着会攒下无用的空集合
            if (stack.isEmpty()) {
                creating.remove();
            }
        }
    }

    /**
     * 构造器选择：@Inject 构造器 → 唯一有参构造器 → 无参兜底
     */
    private Object instantiate(BeanDefinition def) throws Exception {
        Class<?> clazz = resolveEnhancedClass(def);
        Class<?> originalClass = def.getBeanClass();

        // 1. @Inject 构造器优先（显式声明）
        Constructor<?> injectCtor = findInjectConstructor(originalClass);
        if (injectCtor != null) {
            Constructor<?> enhancedCtor = clazz.getDeclaredConstructor(injectCtor.getParameterTypes());
            return instantiateWithArgs(def, enhancedCtor);
        }

        // 2. 无参构造器存在 → 用它（Spring 语义：无 @Inject 时优先默认构造器）
        try {
            return clazz.getDeclaredConstructor().newInstance();
        } catch (NoSuchMethodException e) {
            // 3. 无无参构造器 → 唯一有参构造器隐式注入
            List<Constructor<?>> candidates = Arrays.stream(clazz.getDeclaredConstructors())
                    .filter(c -> c.getParameterCount() > 0)
                    .toList();
            if (candidates.size() == 1) {
                return instantiateWithArgs(def, candidates.getFirst());
            }
            throw new SystemRuntimeException("类不可实例化（无无参构造器且存在多个有参构造器）: "
                    + clazz.getName(), new IllegalStateException());
        }
    }

    private Constructor<?> findInjectConstructor(Class<?> clazz) {
        Constructor<?> injectCtor = null;
        for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
            if (ctor.isAnnotationPresent(Inject.class)) {
                if (injectCtor != null) {
                    throw new SystemRuntimeException("存在多个 @Inject 构造器: " + clazz.getName(),
                            new IllegalStateException());
                }
                injectCtor = ctor;
            }
        }
        return injectCtor;
    }

    private boolean hasAsyncMethods(Class<?> clazz) {
        return Arrays.stream(clazz.getDeclaredMethods())
                .anyMatch(m -> m.isAnnotationPresent(Async.class));
    }

    private boolean hasTrace(Class<?> clazz) {
        if (clazz.isAnnotationPresent(Trace.class)) {
            return true;
        }
        if (Arrays.stream(clazz.getDeclaredMethods())
                .anyMatch(m -> m.isAnnotationPresent(Trace.class))) {
            return true;
        }
        return Arrays.stream(clazz.getDeclaredFields())
                .anyMatch(f -> f.isAnnotationPresent(Trace.class));
    }

    private boolean hasListenException(Class<?> clazz) {
        if (clazz.isAnnotationPresent(ListenException.class)) {
            return true;
        }
        return Arrays.stream(clazz.getDeclaredMethods())
                .anyMatch(m -> m.isAnnotationPresent(ListenException.class));
    }

    /**
     * 构建 Trace 拦截 matcher：<br>
     * ① 方法级 @Trace → 拦截该方法<br>
     * ② 类级 @Trace → 拦截该类所有方法<br>
     * ③ 字段级 @Trace → 仅拦截<b>字节码中写该字段</b>的方法（PUTFIELD 静态分析）
     */
    private ElementMatcher.Junction<MethodDescription> buildTraceMatcher(Class<?> clazz) {
        Set<String> traceFields = Arrays.stream(clazz.getDeclaredFields())
                .filter(f -> f.isAnnotationPresent(Trace.class))
                .map(Field::getName)
                .collect(java.util.stream.Collectors.toSet());
        Map<TraceAnalyzer.MethodSignature, Set<String>> writtenFields =
                traceFields.isEmpty() ? Map.of() : TraceAnalyzer.writtenFieldsByMethod(clazz);

        return ElementMatchers.<MethodDescription>isAnnotatedWith(Trace.class)
                .or(method -> {
                    var declaring = method.getDeclaringType().asErasure();
                    if (declaring.getDeclaredAnnotations().isAnnotationPresent(Trace.class)) {
                        return true;
                    }
                    if (traceFields.isEmpty()) {
                        return false;
                    }
                    var signature = new TraceAnalyzer.MethodSignature(
                            method.getName(), method.getDescriptor());
                    Set<String> written = writtenFields.getOrDefault(signature, Set.of());
                    return written.stream().anyMatch(traceFields::contains);
                })
                .and(ElementMatchers.not(ElementMatchers.isDeclaredBy(Object.class)));
    }

    private Class<?> resolveEnhancedClass(BeanDefinition def) {
        Class<?> clazz = def.getBeanClass();
        boolean doAsync = asyncEnabled && taskExecutorRegistry != null && hasAsyncMethods(clazz);
        boolean doTrace = traceEnabled && traceHandlerRegistry != null && hasTrace(clazz);
        boolean doListen = exceptionListenerEnabled && hasListenException(clazz);
        if (!doAsync && !doTrace && !doListen) {
            return clazz;
        }
        // 一次增强、链式多拦截器：避免嵌套增强导致子类方法注解丢失
        Map<ElementMatcher<? super MethodDescription>, Object> delegates = new LinkedHashMap<>();
        if (doAsync) {
            delegates.put(ElementMatchers.isAnnotatedWith(Async.class),
                    new AsyncInvocationHandler(taskExecutorRegistry));
        }
        if (doTrace) {
            delegates.put(buildTraceMatcher(clazz), new TraceInvocationHandler(traceHandlerRegistry));
        }
        if (doListen) {
            delegates.put(LISTEN_MATCHER, new ExceptionListenerInvocationHandler());
        }
        return AgentEnhancer.enhanceClass(clazz, delegates);
    }

    private Object instantiateWithArgs(BeanDefinition def, Constructor<?> ctor) throws Exception {
        Class<?>[] paramTypes = ctor.getParameterTypes();
        Parameter[] params = ctor.getParameters();
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            Qualifier qualifier = params[i].getAnnotation(Qualifier.class);
            if (qualifier != null) {
                args[i] = getBean(qualifier.value());            // @Qualifier 优先：按名
                if (args[i] == null) {
                    throw new SystemRuntimeException("构造器参数 @Qualifier 未找到 Bean: "
                            + qualifier.value(), new IllegalStateException());
                }
            } else {
                args[i] = getBean(paramTypes[i]);                // 按类型（现状）
                if (args[i] == null) {
                    throw new SystemRuntimeException("构造器参数无法解析: " + def.getBeanName()
                            + " 参数[" + i + "] 类型 " + paramTypes[i].getName(),
                            new IllegalStateException());
                }
            }
        }
        ctor.setAccessible(true);
        return ctor.newInstance(args);
    }

    public void refresh() {
        for (BeanDefinition def : registry) {
            if (def.getLifecycle() == SINGLETON && !def.isLazyInit()) {
                getBean(def.getBeanName());
            }
        }
    }
}
