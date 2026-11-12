package org.wutong.tiangong.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wutong.tiangong.annotations.annotation.framework.Component;
import org.wutong.tiangong.annotations.annotation.framework.inject.Inject;
import org.wutong.tiangong.common.agent.context.AgentContext;
import org.wutong.tiangong.common.exception.SystemRuntimeException;
import org.wutong.tiangong.common.sandbox.ExecOptions;
import org.wutong.tiangong.common.sandbox.ExecResult;
import org.wutong.tiangong.core.container.context.framework.ApplicationFrameworkContext;
import org.wutong.tiangong.core.script.ScriptRuntime;
import org.wutong.tiangong.core.script.ScriptRuntimeRegistry;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 脚本运行时注册表的<b>容器装配链路</b>验证。<br>
 * <br>
 * 与 {@link SkillRegistryTest} 同理：注册表是「refresh 前注册 → refresh 后收集 Bean」，
 * 这条链路手工 new 测不到，必须在真实容器里验。
 */
@TianGongApplicationTestStarter
class ScriptRuntimeRegistryTest {

    @Inject
    ApplicationFrameworkContext context;

    @Test
    @DisplayName("容器装配：注册表在 refresh 后仍可取到，且 Bean 形态运行时已收集")
    void beanRuntimesAreCollected() {
        ScriptRuntimeRegistry registry =
                context.getContainer().getBean(ScriptRuntimeRegistry.BEAN_NAME);

        assertNotNull(registry, "脚本运行时注册表应在 refresh 之前注册进容器");
        assertEquals("demo-runtime", registry.require("demo").name(),
                "实现 ScriptRuntime 的容器 Bean 应在 refresh 之后被收集；"
                        + "收集不到则第三方技能脚本全部无法执行");
    }

    @Test
    @DisplayName("require 缺失后缀时抛明确异常，且点名是哪个后缀")
    void requireNamesMissingExtension() {
        ScriptRuntimeRegistry registry =
                context.getContainer().getBean(ScriptRuntimeRegistry.BEAN_NAME);

        SystemRuntimeException e = assertThrows(SystemRuntimeException.class,
                () -> registry.require("ts"));

        assertTrue(e.getMessage().contains("ts"),
                "错误信息必须点名缺失的后缀，否则无从判断缺的是什么工具链");
    }

    @Test
    @DisplayName("沙箱注册表也已注册进容器：脚本运行时要在沙箱里执行第三方代码")
    void sandboxRegistryIsAvailable() {
        assertNotNull(context.getContainer().getBean("sandboxRegistry"),
                "沙箱注册表应可被注入；脚本运行时不能反射 new 沙箱，否则其 @Inject 字段为 null");
    }

    // ==================== 测试用 Bean ====================

    @Component
    public static class DemoRuntime implements ScriptRuntime {

        @Override
        public List<String> supportedExtensions() {
            return List.of("demo");
        }

        @Override
        public ExecResult run(Path script, List<String> args, AgentContext ctx, ExecOptions options) {
            return ExecResult.failed("demo-runtime", "测试桩不执行", 0);
        }

        @Override
        public String name() {
            return "demo-runtime";
        }
    }
}
