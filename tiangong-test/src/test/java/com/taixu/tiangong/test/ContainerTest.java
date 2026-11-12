package com.taixu.tiangong.test;

import org.junit.jupiter.api.Test;
import com.taixu.tiangong.annotations.annotation.definition.BeanDefinition;
import com.taixu.tiangong.annotations.annotation.definition.Lifecycle;
import com.taixu.tiangong.annotations.annotation.framework.Primary;
import com.taixu.tiangong.annotations.processor.registry.BeanDefinitionRegistry;
import com.taixu.tiangong.common.exception.SystemRuntimeException;
import com.taixu.tiangong.core.container.FrameworkContainer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 框架容器机制测试。<br>
 * 覆盖：Bean 注册与获取、Singleton 生命周期、@Primary 优先级、循环依赖检测、PROTOTYPE 生命周期。
 */
class ContainerTest {

    /** 创建容器（无 AnnotationProcessor，纯 Bean 管理测试） */
    private FrameworkContainer containerFor(BeanDefinition... defs) {
        BeanDefinitionRegistry registry = new BeanDefinitionRegistry();
        for (BeanDefinition def : defs) {
            registry.register(def);
        }
        return FrameworkContainer.create(registry, c -> {});
    }

    private BeanDefinition singleton(String name, Class<?> clazz) {
        return BeanDefinition.builder()
                .beanName(name)
                .beanClass(clazz)
                .lifecycle(Lifecycle.SINGLETON)
                .lazyInit(false)
                .build();
    }

    private BeanDefinition prototype(String name, Class<?> clazz) {
        return BeanDefinition.builder()
                .beanName(name)
                .beanClass(clazz)
                .lifecycle(Lifecycle.PROTOTYPE)
                .lazyInit(false)
                .build();
    }

    // ==================== 基础注册与获取 ====================

    @Test
    void getBeanByNameReturnsInstance() {
        FrameworkContainer container = containerFor(singleton("foo", Foo.class));
        Foo foo = container.getBean("foo", Foo.class);
        assertNotNull(foo, "按名称获取应返回 Bean 实例");
    }

    @Test
    void getBeanByTypeReturnsInstance() {
        FrameworkContainer container = containerFor(singleton("foo", Foo.class));
        Foo foo = container.getBean(Foo.class);
        assertNotNull(foo, "按类型获取应返回 Bean 实例");
    }

    @Test
    void getBeanUnknownNameReturnsNull() {
        FrameworkContainer container = containerFor(singleton("foo", Foo.class));
        Object bean = container.getBean("nonexistent");
        assertNull(bean, "不存在的 Bean 名称应返回 null");
    }

    @Test
    void getBeanUnknownTypeReturnsNull() {
        FrameworkContainer container = containerFor();
        Object bean = container.getBean(Foo.class);
        assertNull(bean, "不存在的 Bean 类型应返回 null");
    }

    // ==================== Singleton 生命周期 ====================

    @Test
    void singletonReturnsSameInstance() {
        FrameworkContainer container = containerFor(singleton("foo", Foo.class));
        Foo a = container.getBean("foo", Foo.class);
        Foo b = container.getBean("foo", Foo.class);
        assertSame(a, b, "SINGLETON 应返回同一实例");
    }

    @Test
    void prototypeReturnsNewInstance() {
        FrameworkContainer container = containerFor(prototype("proto", Foo.class));
        Foo a = container.getBean("proto", Foo.class);
        Foo b = container.getBean("proto", Foo.class);
        assertNotSame(a, b, "PROTOTYPE 应返回新实例");
    }

    // ==================== @Primary 优先级 ====================

    @Test
    void primaryBeanSelectedWhenMultipleCandidates() {
        FrameworkContainer container = containerFor(
                singleton("defaultImpl", DefaultImpl.class),
                singleton("primaryImpl", PrimaryImpl.class)
        );
        Greetable bean = container.getBean(Greetable.class);
        assertTrue(bean instanceof PrimaryImpl, "按类型获取时应优先返回 @Primary Bean");
    }

    @Test
    void primaryOrderRespectedWhenMultiplePrimaries() {
        FrameworkContainer container = containerFor(
                singleton("low", LowPriorityPrimary.class),
                singleton("high", HighPriorityPrimary.class)
        );
        Greetable bean = container.getBean(Greetable.class);
        assertTrue(bean instanceof HighPriorityPrimary, "多个 @Primary 时取 value 最小者");
    }

    // ==================== 循环依赖检测 ====================

    @Test
    void circularDependencyThrows() {
        FrameworkContainer container = containerFor(
                singleton("a", CycleA.class),
                singleton("b", CycleB.class)
        );
        assertThrows(SystemRuntimeException.class,
                () -> container.getBean("a", CycleA.class),
                "循环依赖应抛 SystemRuntimeException");
    }

    // ==================== 手动 register ====================

    @Test
    void registerOverridesSingletonInstance() {
        // register() 往 singletonCache 放实例，需先注册 BeanDefinition 才能 getBean
        FrameworkContainer container = containerFor(singleton("foo", Foo.class));
        Foo autoCreated = container.getBean("foo", Foo.class);
        Foo manual = new Foo();
        container.register("foo", manual);
        Foo retrieved = container.getBean("foo", Foo.class);
        assertSame(manual, retrieved, "register 后应覆盖自动创建的实例");
        assertNotSame(autoCreated, retrieved);
    }

    // ==================== refresh ====================

    @Test
    void refreshCreatesNonLazySingletons() {
        BeanDefinitionRegistry registry = new BeanDefinitionRegistry();
        registry.register(singleton("foo", Foo.class));
        registry.register(BeanDefinition.builder()
                .beanName("lazy")
                .beanClass(LazyFoo.class)
                .lifecycle(Lifecycle.SINGLETON)
                .lazyInit(true)
                .build());
        FrameworkContainer container = FrameworkContainer.create(registry, c -> {});
        container.refresh();
        assertTrue(Foo.instantiated, "非 lazy singleton 应在 refresh 时创建");
        assertFalse(LazyFoo.instantiated, "lazy singleton 不应在 refresh 时创建");
        // 清理静态标志
        Foo.instantiated = false;
        LazyFoo.instantiated = false;
    }

    // ==================== 测试用 Bean 类 ====================

    public static class Foo {
        static boolean instantiated = false;
        public Foo() { instantiated = true; }
    }

    public static class LazyFoo {
        static boolean instantiated = false;
        public LazyFoo() { instantiated = true; }
    }

    public interface Greetable {
        String greet();
    }

    public static class DefaultImpl implements Greetable {
        @Override
        public String greet() { return "default"; }
    }

    @Primary
    public static class PrimaryImpl implements Greetable {
        @Override
        public String greet() { return "primary"; }
    }

    @Primary(value = 10)
    public static class LowPriorityPrimary implements Greetable {
        @Override
        public String greet() { return "low"; }
    }

    @Primary(value = 1)
    public static class HighPriorityPrimary implements Greetable {
        @Override
        public String greet() { return "high"; }
    }

    /** 循环依赖测试：CycleA 构造器依赖 CycleB，CycleB 构造器依赖 CycleA */
    public static class CycleA {
        public CycleA(CycleB b) {}
    }

    public static class CycleB {
        public CycleB(CycleA a) {}
    }
}
