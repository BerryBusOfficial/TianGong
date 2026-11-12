package org.wutong.tiangong.skill;

import org.wutong.tiangong.core.skill.Skill;

import java.util.List;

/**
 * 由 {@code SKILL.md} 解析出的技能。<br>
 * <br>
 * 用 record 实现 {@link Skill}：组件名与接口方法一一对应，无需额外桥接代码。<br>
 * <br>
 * <h2>为什么要带 {@code source}</h2>
 * 同一份技能可能来自多个目录（内置 / 用户级 / 项目级），重名时日志里
 * 只有 id 会让人分不清冲突的是哪两个文件。带上来源路径，排查成本从
 * 「翻遍所有目录」降到一行。
 *
 * @param id          技能标识，取 frontmatter 的 {@code name}；缺失时回退为目录名
 * @param description 一句话描述，进目录
 * @param triggers    触发提示词，仅展示用
 * @param toolNames   依赖的工具名
 * @param body        Markdown 正文，命中后才加载
 * @param source      来源文件路径（诊断用，不参与接口契约）
 */
public record MarkdownSkill(
        String id,
        String description,
        List<String> triggers,
        List<String> toolNames,
        String body,
        String source
) implements Skill {
}
