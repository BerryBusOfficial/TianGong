package org.wutong.tiangong.skill.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wutong.tiangong.core.function.AgentFunctionDefinition;
import org.wutong.tiangong.core.function.FunctionRegistry;
import org.wutong.tiangong.core.skill.Skill;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 宿主工具映射验证。<br>
 * <br>
 * 这里锁的是 L2 的立身之本：<b>技能声明的工具名与宿主的命名不一致时，仍能接上</b>。
 * 接不上会怎样？技能看起来完整，模型一调用就失败 —— 而且失败原因对模型不可见，
 * 它只会一遍遍重试。
 */
class HostToolMappingTest {

    private static AgentFunctionDefinition fn(String name) {
        return new AgentFunctionDefinition(name, "bean", "desc", null, null, List.of());
    }

    private static Skill skill(String id, List<String> toolNames) {
        return new Skill() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String description() {
                return "测试技能";
            }

            @Override
            public List<String> toolNames() {
                return toolNames;
            }

            @Override
            public String body() {
                return "正文";
            }
        };
    }

    @Test
    @DisplayName("宿主用生态同名注册时精确命中，不走别名（事实优先于猜测）")
    void exactMatchWins() {
        FunctionRegistry registry = new FunctionRegistry();
        registry.register(fn("Read"));
        registry.register(fn("sys_read"));

        AgentFunctionDefinition hit = HostToolMapping.resolve("Read", registry).orElseThrow();

        assertEquals("Read", hit.getName());
    }

    @Test
    @DisplayName("宿主命名不同时按别名回退：技能写 Read，宿主叫 sys_read")
    void fallsBackToAlias() {
        FunctionRegistry registry = new FunctionRegistry();
        registry.register(fn("sys_read"));

        AgentFunctionDefinition hit = HostToolMapping.resolve("Read", registry).orElseThrow();

        assertEquals("sys_read", hit.getName(),
                "别名回退是这一层存在的理由；接不上技能就是废的");
    }

    @Test
    @DisplayName("别名匹配大小写不敏感：技能里 read / READ 都要能接上")
    void aliasIsCaseInsensitive() {
        FunctionRegistry registry = new FunctionRegistry();
        registry.register(fn("sys_read"));

        assertTrue(HostToolMapping.resolve("read", registry).isPresent());
        assertTrue(HostToolMapping.resolve("READ", registry).isPresent());
    }

    @Test
    @DisplayName("完全没有对应工具时返回空，不抛异常")
    void missingReturnsEmpty() {
        FunctionRegistry registry = new FunctionRegistry();

        assertTrue(HostToolMapping.resolve("Write", registry).isEmpty());
    }

    @Test
    @DisplayName("审计：解析到的与缺失的分开报，缺失项要出现在正文提示里")
    void auditSplitsResolvedAndMissing() {
        FunctionRegistry registry = new FunctionRegistry();
        registry.register(fn("sys_read"));
        registry.register(fn("Bash"));

        HostToolMapping.Report report = HostToolMapping.audit(
                skill("s", List.of("Read", "Bash", "Write")), registry);

        assertEquals(List.of("Read", "Bash"), report.resolved());
        assertEquals(List.of("Write"), report.missing());
        assertTrue(report.hasMissing());
        assertTrue(report.footer().contains("Write"),
                "提示里必须点名缺哪个工具，否则模型不知道该避开什么");
    }

    @Test
    @DisplayName("未声明工具的技能：空报告、无提示（不是问题，不该告警）")
    void auditOfToolLessSkillIsEmpty() {
        HostToolMapping.Report report =
                HostToolMapping.audit(skill("s", List.of()), new FunctionRegistry());

        assertTrue(report.empty());
        assertFalse(report.hasMissing());
        assertEquals("", report.footer());
    }

    @Test
    @DisplayName("解析顺序与声明顺序一致（调用方按序取工具）")
    void resolveAllKeepsOrder() {
        FunctionRegistry registry = new FunctionRegistry();
        registry.register(fn("Grep"));
        registry.register(fn("sys_read"));
        registry.register(fn("Bash"));

        List<AgentFunctionDefinition> resolved =
                HostToolMapping.resolveAll(skill("s", List.of("Bash", "Read", "Grep", "Write")), registry);

        assertEquals(List.of("Bash", "sys_read", "Grep"),
                resolved.stream().map(AgentFunctionDefinition::getName).toList());
    }
}
