package com.taixu.tiangong.test;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.*;

/**
 * Agent 集成测试入口（{@link TianGongApplicationTestStarter} 的短别名）。<br>
 * 标在测试类上：自动加载配置、创建完整应用上下文，并按 {@code @Inject} 注入依赖。<br>
 * <br>
 * 与 {@code @TianGongApplicationTestStarter} 等价；新增短名是为了让业务测试读起来像
 * 「这是一个 Agent 测试」，而不是先理解一串框架装配术语。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ExtendWith(TianGongExtension.class)
public @interface AgentTest {
}
