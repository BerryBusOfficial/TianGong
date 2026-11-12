package com.taixu.tiangong.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.taixu.tiangong.annotations.annotation.framework.Component;
import com.taixu.tiangong.annotations.annotation.framework.inject.Inject;
import com.taixu.tiangong.common.agent.context.AgentContext;
import com.taixu.tiangong.common.exception.SystemRuntimeException;
import com.taixu.tiangong.common.sandbox.CommandLine;
import com.taixu.tiangong.common.sandbox.ExecOptions;
import com.taixu.tiangong.common.sandbox.ExecResult;
import com.taixu.tiangong.common.sandbox.Sandbox;
import com.taixu.tiangong.core.container.context.framework.ApplicationFrameworkContext;
import com.taixu.tiangong.core.sandbox.CommandLineResolver;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「沙箱归框架管理」的端到端验证：用户只标 {@code @Component}，框架自动采用。<br>
 * <br>
 * 这里测的是<b>装配链路</b>，不是沙箱执行行为（后者见 {@link LocalSandboxTest}）。<br>
 * <br>
 * 之所以必须用真实上下文而不是手工 new：装配发生在
 * {@code ApplicationFrameworkContext.create()} 里、且刻意排在 {@code container.refresh()}
 * <b>之后</b>——早于这一步 Bean 还不可解析。手工调
 * {@code setCommandLineResolver} 能过，证明不了框架会自己去取。
 */
@TianGongApplicationTestStarter
class SandboxFrameworkManagedTest {

    @Inject
    ApplicationFrameworkContext context;

    @Test
    @DisplayName("命令解析器：标 @Component 后被框架自动装配，无需手工注册")
    void commandResolverIsAutoRegistered() {
        assertNotNull(context, "应用上下文应被注入");

        Optional<CommandLineResolver> resolver = context.getSandboxRegistry().commandLineResolver();

        assertTrue(resolver.isPresent(),
                "标了 @Component 的 CommandLineResolver 应被框架自动采用；"
                        + "为空说明装配未接上，COMMAND 节点会在执行期报错");
        assertInstanceOf(StubCommandResolver.class, resolver.get(),
                "应解析到测试声明的那个实现");
    }

    @Test
    @DisplayName("沙箱实例：@Sandbox(box=...) 解析出的是容器 Bean，不是反射 new 出来的")
    void sandboxInstanceComesFromContainer() {
        Sandbox fromRegistry = context.getSandboxRegistry().getOrCreate(StubSandbox.class);
        Sandbox bean = context.getContainer().getBean(StubSandbox.class);

        assertNotNull(bean, "StubSandbox 标了 @Component，容器里应有它的 Bean");
        // 若走反射 new，这里是两个不同对象 —— 该断言能区分两条路径
        assertSame(bean, fromRegistry,
                "注册表返回的值必须与容器 Bean 同一引用；"
                        + "不同即说明仍在反射 new，沙箱实现里的 @Inject 字段会静默为 null");
    }

    @Test
    @DisplayName("沙箱实例：按名可查，且与容器 Bean 是同一个（单例语义成立）")
    void sandboxIsRegisteredByNameAndSingleton() {
        Sandbox first = context.getSandboxRegistry().getOrCreate(StubSandbox.class);
        Sandbox second = context.getSandboxRegistry().getOrCreate(StubSandbox.class);

        assertSame(first, second, "同名沙箱重复解析应返回同一实例");
        assertTrue(context.getSandboxRegistry().find(StubSandbox.NAME).isPresent(),
                "应已按 name() 登记，可供节点方法 find(\"stub\") 动态选用");
    }

    @Test
    @DisplayName("对照组：反射路径确实产生不同实例（证明上一条 assertSame 有区分力）")
    void reflectionPathYieldsDifferentInstance() {
        Sandbox bean = context.getContainer().getBean(StubSandbox.class);
        Sandbox reflected = context.getSandboxRegistry().instantiate(StubSandbox.class);

        // 少了这条，上面的 assertSame 通过可能是巧合 —— 必须证明两条路径结果确实不同
        assertNotSame(bean, reflected,
                "反射 new 与容器 Bean 必须是不同对象；若相同，说明 assertSame 那条断言无区分力");
    }

    @Test
    @DisplayName("供给器返回 null 时抛明确异常，而不是在上游 NPE")
    void nullSupplierFailsLoudly() {
        var registry = context.getSandboxRegistry();
        registry.setInstanceSupplier(type -> null);

        SystemRuntimeException ex = assertThrows(SystemRuntimeException.class,
                () -> registry.getOrCreate(StubSandbox.class));

        assertTrue(ex.getMessage().contains("null"),
                "异常信息应点明是供给器返回了 null，实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("默认沙箱仍可用：未标 @Sandbox 的命令节点走 local")
    void defaultSandboxRemainsAvailable() {
        Sandbox fallback = context.getSandboxRegistry().defaultSandbox();
        assertNotNull(fallback, "默认沙箱不该为空");
        assertEquals("local", fallback.name());
    }

    // ==================== 测试用 Bean ====================

    /**
     * 命令解析器实现：只标 {@code @Component}，不做任何手工注册。
     */
    @Component
    public static class StubCommandResolver implements CommandLineResolver {
        @Override
        public Optional<CommandLine> resolve(AgentContext ctx) {
            return Optional.empty();
        }
    }

    /**
     * 沙箱实现：只标 {@code @Component}。<br>
     * 之所以用 {@code assertSame} 而不是看行为，是因为两条路径（容器 / 反射）
     * 在行为上完全一致，只有引用能区分。
     */
    @Component
    public static class StubSandbox implements Sandbox {

        static final String NAME = "stub";

        @Override
        public String name() {
            return NAME;
        }

        @Override
        public ExecResult exec(CommandLine cmd, AgentContext ctx) {
            return ExecResult.of(NAME, "", 0, false, 0, 0);
        }

        @Override
        public ExecResult exec(CommandLine cmd, AgentContext ctx, ExecOptions options) {
            return exec(cmd, ctx);
        }
    }
}
