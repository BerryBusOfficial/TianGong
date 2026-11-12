package com.taixu.tiangong.skill.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.taixu.tiangong.core.function.AgentFunctionDefinition;
import com.taixu.tiangong.core.function.FunctionRegistry;
import com.taixu.tiangong.core.skill.Skill;
import com.taixu.tiangong.core.skill.SkillRegistry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 技能展开工具验证。<br>
 * <br>
 * 这里锁的是「模型驱动命中」这条路径的可用性：目录里只有一句话，
 * 模型判断用得上就调 {@code activate_skill} 取正文。<br>
 * 同时锁住一条容易被忽略的点：<b>正文必须带上工具缺失提示</b>。
 * 否则模型会去调宿主根本没有的工具，白费一整轮。
 */
class SkillToolTest {

    private static Skill skill(String id, String body, List<String> toolNames) {
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
                return body;
            }
        };
    }

    private static SkillTool tool(SkillRegistry skills, FunctionRegistry functions) {
        SkillTool tool = new SkillTool();
        tool.skills = skills;
        tool.functions = functions;
        return tool;
    }

    @Test
    @DisplayName("命中即返回完整正文")
    void returnsBody() {
        SkillRegistry skills = new SkillRegistry();
        skills.register(skill("mvn-verify", "跑 mvn -q test", List.of()));

        assertEquals("跑 mvn -q test", tool(skills, new FunctionRegistry()).activate("mvn-verify"));
    }

    @Test
    @DisplayName("正文末尾追加缺失工具提示，避免模型空转")
    void appendsMissingToolNotice() {
        SkillRegistry skills = new SkillRegistry();
        skills.register(skill("s", "步骤正文", List.of("Bash", "Write")));
        FunctionRegistry functions = new FunctionRegistry();
        functions.register(new AgentFunctionDefinition("Bash", "bean", "d", null, null, List.of()));

        String out = tool(skills, functions).activate("s");

        assertTrue(out.startsWith("步骤正文"));
        assertTrue(out.contains("Write"), "必须点名缺失的工具，实际返回: " + out);
        assertFalse(out.contains("Bash"), "可用工具不该出现在缺失清单里，实际返回: " + out);
    }

    @Test
    @DisplayName("未知 id 回报可用列表，让模型能自我纠正")
    void unknownIdListsAvailable() {
        SkillRegistry skills = new SkillRegistry();
        skills.register(skill("a", "A", List.of()));
        skills.register(skill("b", "B", List.of()));

        String out = tool(skills, new FunctionRegistry()).activate("nope");

        assertTrue(out.contains("a"), "应列出可用技能，实际: " + out);
        assertTrue(out.contains("b"));
    }

    @Test
    @DisplayName("注册表未注入时不抛异常，返回明确文字")
    void nullRegistryIsHandled() {
        assertEquals("技能注册表不可用", tool(null, null).activate("any"));
    }
}
