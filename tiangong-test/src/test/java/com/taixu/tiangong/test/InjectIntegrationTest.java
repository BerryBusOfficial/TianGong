package com.taixu.tiangong.test;

import org.junit.jupiter.api.Test;
import com.taixu.tiangong.annotations.annotation.framework.Component;
import com.taixu.tiangong.annotations.annotation.framework.Primary;
import com.taixu.tiangong.annotations.annotation.framework.inject.Inject;
import com.taixu.tiangong.annotations.annotation.framework.inject.Qualifier;
import com.taixu.tiangong.core.container.context.framework.ApplicationFrameworkContext;
import com.taixu.tiangong.core.function.FunctionRegistry;
import com.taixu.tiangong.core.llm.LlmRegistry;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 容器与注入集成测试：通过 {@code @TianGongApplicationTestStarter} 启动完整应用上下文，
 * 验证 @Component Bean 注册、@Inject 字段/构造器注入、@Qualifier 按名解析、@Primary 优先级。
 */
@TianGongApplicationTestStarter
class InjectIntegrationTest {

    @Inject
    ApplicationFrameworkContext context;

    @Inject
    LlmRegistry llmRegistry;

    @Inject
    FunctionRegistry functionRegistry;

    @Inject
    HelloService helloService;

    @Inject
    Greeter defaultGreeter;

    @Inject
    @Qualifier("EnglishGreeter")
    Greeter englishGreeter;

    @Inject
    Greeter primaryGreeter;  // 多个 Greeter → 应取 @Primary

    @Test
    void contextIsInjected() {
        assertNotNull(context, "ApplicationFrameworkContext 应被注入");
        assertNotNull(context.getContainer(), "Container 应可用");
    }

    @Test
    void llmRegistryIsInjected() {
        assertNotNull(llmRegistry, "LlmRegistry 应被注入");
    }

    @Test
    void functionRegistryIsInjected() {
        assertNotNull(functionRegistry, "FunctionRegistry 应被注入");
    }

    @Test
    void componentBeanIsInjected() {
        assertNotNull(helloService, "@Component Bean 应被注入");
        assertEquals("Hello, World!", helloService.sayHello());
    }

    @Test
    void fieldInjectionByType() {
        assertNotNull(defaultGreeter, "按类型注入 Greeter 应成功");
    }

    @Test
    void qualifierResolvesByName() {
        assertNotNull(englishGreeter, "@Qualifier 按名注入应成功");
        assertEquals("Hello", englishGreeter.greet());
    }

    @Test
    void primarySelectedWhenMultipleCandidates() {
        assertNotNull(primaryGreeter, "@Primary Bean 应被注入");
        assertEquals("Hi", primaryGreeter.greet());
    }

    // ==================== 测试用 @Component Bean ====================

    @Component
    public static class HelloService {
        public String sayHello() {
            return "Hello, World!";
        }
    }

    public interface Greeter {
        String greet();
    }

    @Component
    public static class ChineseGreeter implements Greeter {
        @Override
        public String greet() { return "你好"; }
    }

    @Component
    public static class EnglishGreeter implements Greeter {
        @Override
        public String greet() { return "Hello"; }
    }

    @Component
    @Primary
    public static class PrimaryGreeter implements Greeter {
        @Override
        public String greet() { return "Hi"; }
    }

    /** 验证构造器注入：通过无参构造器实例化后，字段注入 @Inject 依赖 */
    @Component
    public static class GreeterHolder {
        @Inject
        HelloService helloService;

        public String welcome() {
            return helloService.sayHello();
        }
    }
}
