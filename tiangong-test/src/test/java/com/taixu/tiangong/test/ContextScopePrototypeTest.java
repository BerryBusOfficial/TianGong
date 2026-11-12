package com.taixu.tiangong.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.taixu.tiangong.annotations.annotation.definition.Lifecycle;
import com.taixu.tiangong.annotations.annotation.framework.Component;
import com.taixu.tiangong.annotations.annotation.framework.Scope;
import com.taixu.tiangong.annotations.annotation.framework.inject.Inject;
import com.taixu.tiangong.core.container.context.framework.ApplicationFrameworkContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 {@code @Scope} 注解确实驱动了容器的原型作用域。<br>
 * <br>
 * <b>为什么必须专门测</b>：{@link ContainerTest} 里的 PROTOTYPE 用例是手工构造
 * {@code BeanDefinition} 并直接设 {@code lifecycle(PROTOTYPE)}，<b>绕过了注解解析</b>。
 * 而工作流上下文的隔离依赖的是「类上标 {@code @Scope} → 扫描器读出来 → 生效」这条链路
 * —— 中间任何一环断了，上下文就还是单例，并发工作流照样互相污染。<br>
 * <br>
 * 因此这里走<b>真实容器</b>（{@code @TianGongApplicationTestStarter}）而非手工装配。
 */
@TianGongApplicationTestStarter
class ContextScopePrototypeTest {

    @Inject
    ApplicationFrameworkContext context;

    @Test
    @DisplayName("@Scope(PROTOTYPE)：每次取得的是新实例")
    void prototypeYieldsNewInstanceEachTime() {
        Object first = context.getContainer().getBean(PrototypeBean.class);
        Object second = context.getContainer().getBean(PrototypeBean.class);

        assertNotNull(first);
        assertNotSame(first, second,
                "标了 @Scope(PROTOTYPE) 的类每次 getBean 都应返回新实例；"
                        + "相同说明 @Scope 注解未被扫描器读取，工作流上下文隔离会失效");
    }

    @Test
    @DisplayName("对照组：默认（未标 @Scope）仍是单例")
    void defaultScopeRemainsSingleton() {
        Object first = context.getContainer().getBean(DefaultBean.class);
        Object second = context.getContainer().getBean(DefaultBean.class);

        assertSame(first, second,
                "未标 @Scope 的类默认应为单例；"
                        + "若这里也变成新实例，说明上面的 PROTOTYPE 断言失去区分力");
    }

    @Test
    @DisplayName("原型实例仍走完整注入：字段注入与单例 Bean 引用均正常")
    void prototypeStillGetsInjected() {
        PrototypeBean bean = context.getContainer().getBean(PrototypeBean.class);

        assertNotNull(bean.collaborator,
                "原型作用域每次新建实例，但仍须跑 AnnotationProcessor —— "
                        + "否则 @Inject 字段为 null，工作流上下文会缺依赖");
    }

    // ==================== 测试用 Bean ====================

    @Component
    @Scope(Lifecycle.PROTOTYPE)
    public static class PrototypeBean {
        @Inject
        Collaborator collaborator;
    }

    /** 对照组：不标 @Scope，默认单例 */
    @Component
    public static class DefaultBean {
    }

    @Component
    public static class Collaborator {
    }
}
