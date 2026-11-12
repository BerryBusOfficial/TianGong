package org.wutong.tiangong.skill;

import lombok.extern.slf4j.Slf4j;
import org.wutong.tiangong.annotations.annotation.framework.AutoConfiguration;
import org.wutong.tiangong.annotations.annotation.framework.enable.EnableSkill;
import org.wutong.tiangong.core.config.ConfigHolder;
import org.wutong.tiangong.core.container.context.framework.FrameworkConfiguration;
import org.wutong.tiangong.core.container.context.framework.FrameworkConfigurator;
import org.wutong.tiangong.core.skill.Skill;
import org.wutong.tiangong.core.skill.SkillRegistry;
import org.wutong.tiangong.skill.source.DirectorySkillSource;
import org.wutong.tiangong.skill.tool.ShellTool;
import org.wutong.tiangong.skill.tool.SkillTool;

import java.nio.file.Path;
import java.util.List;

/**
 * 目录式技能的自动装配。<br>
 * <br>
 * 执行时机在容器 refresh <b>之前</b>（{@code @AutoConfiguration} 的既有契约），
 * 因此这里能拿到 {@link SkillRegistry} —— 它由框架核心在 refresh 之前创建并注册，
 * 正是为了给这一类模块留出装配窗口。<br>
 * <br>
 * 受 {@link EnableSkill} 开关控制：未标注或 {@code value=false} 时不扫描。
 * 默认关闭，避免应用只是把本模块放进了 classpath 就被迫吃下一批技能。<br>
 * <br>
 *
 * <h2>顺带装配的两个宿主工具</h2>
 * {@link ShellTool}（{@code Bash} / {@code Read}）与 {@link SkillTool}（{@code activate_skill}）
 * 也在这里注册，而不是靠 {@code @Component} 扫描 ——
 * {@code Bash} 是<b>任意命令执行</b>，绝不能因为模块在 classpath 上就被被动注入。
 * 开关与技能一致：不开技能就不给工具。
 */
@Slf4j
@AutoConfiguration
public class SkillAutoConfiguration implements FrameworkConfiguration {

    /** 默认扫描目录（相对工作目录） */
    public static final String DEFAULT_PATH = "skills";

    @Override
    public void configure(FrameworkConfigurator configurator) {
        if (!isSkillEnabled(configurator)) {
            return;
        }
        configurator.registerBean(ShellTool.class);
        configurator.registerBean(SkillTool.class);

        String path = ConfigHolder.getValueOrDefault("agent.skills.path", String.class, DEFAULT_PATH);
        List<Skill> skills = DirectorySkillSource.load(Path.of(path));
        if (skills.isEmpty()) {
            return;
        }
        SkillRegistry registry = configurator.container().getBean(SkillRegistry.BEAN_NAME);
        if (registry == null) {
            log.warn("未取得技能注册表，已解析的 {} 个技能未注册", skills.size());
            return;
        }
        for (Skill skill : skills) {
            registry.register(skill);
        }
        log.info("已注册 {} 个目录式技能", skills.size());
    }

    private static boolean isSkillEnabled(FrameworkConfigurator configurator) {
        return configurator.scanner().getEnableMetadata().stream()
                .anyMatch(meta -> meta.enableAnnotation() == EnableSkill.class && meta.enabled());
    }
}
