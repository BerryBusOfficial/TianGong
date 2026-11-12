package com.taixu.tiangong.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.taixu.tiangong.annotations.annotation.framework.Component;
import com.taixu.tiangong.annotations.annotation.framework.inject.Inject;
import com.taixu.tiangong.common.exception.SystemRuntimeException;
import com.taixu.tiangong.core.container.context.framework.ApplicationFrameworkContext;
import com.taixu.tiangong.core.function.AgentFunctionDefinition;
import com.taixu.tiangong.core.function.FunctionRegistry;
import com.taixu.tiangong.core.skill.Skill;
import com.taixu.tiangong.core.skill.SkillRegistry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 技能注册表的装配与渐进式披露出口验证。<br>
 * <br>
 * <b>必须走真实容器</b>：{@link SkillRegistry} 的生命周期是「refresh 之前创建并注册 →
 * refresh 之后收集 Bean 形态技能」，中间还要经得起 {@code refresh()} 不清空已注册实例
 * 这个前提。手工 new 出来测，这条链路一个字都覆盖不到。
 */
@TianGongApplicationTestStarter
class SkillRegistryTest {

    @Inject
    ApplicationFrameworkContext context;

    @Test
    @DisplayName("容器装配：refresh 前注册的实例在 refresh 后仍在，且 Bean 形态技能已收集")
    void beanSkillsAreCollectedAfterRefresh() {
        SkillRegistry registry = context.getContainer().getBean(SkillRegistry.BEAN_NAME);

        assertNotNull(registry,
                "技能注册表应在 refresh 之前注册进容器；为 null 说明 refresh() 清空了已注册实例，"
                        + "目录式技能的自动装配窗口也会一起消失");
        assertTrue(registry.find(DemoSkill.ID).isPresent(),
                "实现 Skill 的容器 Bean 应在 refresh 之后被收集；"
                        + "收集不到则 Bean 形态技能全部静默失效");
    }

    @Test
    @DisplayName("catalog：只出一句话描述，不含正文")
    void catalogIsBrief() {
        SkillRegistry registry = context.getContainer().getBean(SkillRegistry.BEAN_NAME);
        String catalog = registry.catalog();

        assertTrue(catalog.contains(DemoSkill.ID), "目录应含技能 id");
        assertTrue(catalog.contains("演示技能"), "目录应含一句话描述");
        assertFalse(catalog.contains("正文第一行"),
                "目录常驻系统提示词，正文绝不能出现在这里 —— 那是渐进式披露要省掉的成本");
    }

    @Test
    @DisplayName("body：命中后才取到正文")
    void bodyLoadedOnDemand() {
        SkillRegistry registry = context.getContainer().getBean(SkillRegistry.BEAN_NAME);

        assertTrue(registry.body(DemoSkill.ID).orElseThrow().contains("正文第一行"));
        assertTrue(registry.body("not-registered").isEmpty(), "未注册的技能取正文应为空");
    }

    @Test
    @DisplayName("functions：解析已注册工具，缺失的跳过且不抛异常")
    void resolvesTools() {
        SkillRegistry registry = new SkillRegistry();
        registry.register(new DemoSkill());

        FunctionRegistry functions = new FunctionRegistry();
        functions.register(new AgentFunctionDefinition(
                "read_file", "demo", "读文件",
                new Object(), toStringMethod(), List.of()));

        assertEquals(1, registry.functions(DemoSkill.ID, functions).size(),
                "已注册的工具应被解析出来");
        assertTrue(registry.functions(DemoSkill.ID, new FunctionRegistry()).isEmpty(),
                "工具未注册时应返回空集并告警，而不是抛异常让整条链路断掉");
    }

    @Test
    @DisplayName("重复 id 直接抛异常：静默覆盖会让人分不清生效的是哪一个")
    void duplicateIdIsRejected() {
        SkillRegistry registry = new SkillRegistry();
        registry.register(new DemoSkill());

        assertThrows(SystemRuntimeException.class, () -> registry.register(new DemoSkill()));
    }

    private static java.lang.reflect.Method toStringMethod() {
        try {
            return Object.class.getMethod("toString");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    // ==================== 测试用 Bean ====================

    @Component
    public static class DemoSkill implements Skill {

        static final String ID = "demo-skill";

        @Override
        public String id() {
            return ID;
        }

        @Override
        public String description() {
            return "演示技能";
        }

        @Override
        public List<String> toolNames() {
            return List.of("read_file");
        }

        @Override
        public String body() {
            return "正文第一行\n第二行";
        }
    }
}
