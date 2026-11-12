package org.wutong.tiangong.skill.tool;

import lombok.extern.slf4j.Slf4j;
import org.wutong.tiangong.annotations.annotation.framework.agent.AgentFunction;
import org.wutong.tiangong.annotations.annotation.framework.inject.Inject;
import org.wutong.tiangong.core.function.FunctionRegistry;
import org.wutong.tiangong.core.skill.Skill;
import org.wutong.tiangong.core.skill.SkillRegistry;

import java.util.Optional;

/**
 * 把「展开技能」本身做成工具。<br>
 * <br>
 * <h2>为什么这是命中判定的最优解</h2>
 * 关键词命中必然误判：用户聊到「日志」就被展开 {@code log-inspect}，
 * 而真正需要它的对话可能一个关键词都没出现。<br>
 * 做成工具后，<b>判定权回到模型手里</b> —— 模型看到目录（一句话描述），
 * 判断这次用不用得上，用得上就调 {@code activate_skill} 取正文。
 * 应用不需要猜，框架也不需要提供判定机制（又一次「框架只给接入口」）。<br>
 * <br>
 * 代价是模型要多一次工具调用；换来的零误判，值。<br>
 * <br>
 *
 * <h2>正文末尾为什么会多一段</h2>
 * 技能声明的工具宿主未必都有（框架刻意不提供 {@code Write}）。
 * 若不说明，模型会去调一个不存在的工具，白搭一整轮。
 * 因此按 {@link HostToolMapping#audit} 的结果追加一句「哪些不可用」 ——
 * 报缺失而不是假装完整。<br>
 * <br>
 *
 * <h2>装配方式</h2>
 * 与 {@link ShellTool} 同理：不由扫描器自动装配，由 {@code SkillAutoConfiguration}
 * 在 {@code @EnableSkill} 打开时注册。
 */
@Slf4j
public class SkillTool {

    public static final String ACTIVATE = "activate_skill";

    @Inject
    SkillRegistry skills;

    @Inject
    FunctionRegistry functions;

    @AgentFunction(
            name = ACTIVATE,
            description = "展开指定技能的完整操作指南。先用它拿到步骤再动手，不要凭目录猜步骤。"
    )
    public String activate(
            @AgentFunction.ToolParam(name = "id", description = "技能 id，见可用技能目录") String id
    ) {
        if (skills == null) {
            return "技能注册表不可用";
        }
        Optional<Skill> skill = skills.find(id);
        if (skill.isEmpty()) {
            return "没有这个技能: " + id + "。可用：" + availableIds();
        }
        String body = skill.get().body();
        String footer = HostToolMapping.audit(skill.get(), functions).footer();
        log.info("技能 [{}] 由模型主动展开，正文 {} 字{}",
                id, body.length(), footer.isEmpty() ? "" : "（含工具缺失提示）");
        return body + footer;
    }

    private String availableIds() {
        return skills.values().stream()
                .map(Skill::id)
                .sorted()
                .reduce((a, b) -> a + "、" + b)
                .orElse("（无）");
    }
}
