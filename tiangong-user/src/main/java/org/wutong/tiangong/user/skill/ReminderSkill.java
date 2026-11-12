package org.wutong.tiangong.user.skill;

import org.wutong.tiangong.annotations.annotation.framework.Component;
import org.wutong.tiangong.core.skill.Skill;

import java.util.List;

/**
 * 示例技能（Bean 形态）：定时提醒。<br>
 * <br>
 * 用来端到端演示技能的完整链路：
 * 类实现 {@link Skill} → 被扫描为容器 Bean → refresh 后由
 * {@code SkillRegistry.loadBeans} 收集 → {@code SessionContext.init()} 把
 * 它的<b>一句话描述</b>拼进系统提示词 → 模型命中后由应用调
 * {@code body()} 取正文、{@code functions()} 取它依赖的工具。<br>
 * <br>
 * 用 Bean 而非 {@code SKILL.md} 的理由：正文里要按环境动态拼提示，
 * 注解/文件都只能给常量 —— 这正是框架选接口而非注解的原因之一。
 */
@Component
public class ReminderSkill implements Skill {

    public static final String ID = "reminder";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "设置提醒：到点后弹出系统通知（用户说「提醒我」「记得」「过一会儿」时用）";
    }

    @Override
    public List<String> triggers() {
        return List.of("提醒", "定时", "待会儿");
    }

    /** 依赖的工具：{@code sys_notice} 来自 SystemTool，{@code get_sys_time} 用于对齐当前时间 */
    @Override
    public List<String> toolNames() {
        return List.of("get_sys_time", "sys_notice");
    }

    @Override
    public String body() {
        return """
                # 设置提醒

                1. 先用 get_sys_time 取当前时间，把用户说的相对时间（「十分钟后」）换算成绝对时刻。
                2. 用 sys_notice(title, content) 登记通知。
                   - title：简短，不超过 10 个字
                   - content：用户原始诉求，不要改写
                3. 回复时复述一遍时间与事项，让用户有机会纠正。

                ## 约束
                - 用户没给明确时间就不要猜，先问。
                - 一次只登记一条提醒；用户要多条就分多次调用。
                """;
    }
}
