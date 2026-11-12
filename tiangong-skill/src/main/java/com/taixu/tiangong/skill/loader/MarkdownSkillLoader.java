package com.taixu.tiangong.skill.loader;

import lombok.extern.slf4j.Slf4j;
import com.taixu.tiangong.common.entity.SomeTypeReference;
import com.taixu.tiangong.common.util.YamlUtil;
import com.taixu.tiangong.skill.MarkdownSkill;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 解析 {@code SKILL.md} —— 主流技能包（Claude Skills 及其跟随者）的事实格式。<br>
 * <br>
 * 文件结构：
 * <pre>
 * ---
 * name: pdf-extract
 * description: 从 PDF 抽取文本与表格
 * ---
 * 正文：Markdown 写的操作指南
 * </pre>
 *
 * <h2>适配策略：宽容解析 + 必需项兜底</h2>
 * 生态里的 frontmatter 字段并没有统一标准，同一个语义有 {@code allowed-tools} /
 * {@code allowed_tools} / {@code tools} 等多种写法。这里对<b>同义字段逐个尝试</b>，
 * 而不是绑定某一种方言 —— 绑定任何一家，都会在其他家的包上静默失效。<br>
 * <br>
 * 只有 {@code description} 是必需的：没有描述的技能无法进目录（模型无从判断是否展开），
 * 解析结果为空并告警，而不是塞一个空描述进去污染目录。
 *
 * <h2>不适配套的部分</h2>
 * 正文里引用的<b>脚本</b>（{@code scripts/*.ts} 等）与<b>宿主工具</b>
 * （{@code Bash} / {@code Read} 等）不在这里处理，见
 * {@code ScriptRuntime} 与宿主工具映射 —— 那两层需要运行时，不能靠解析解决。
 */
@Slf4j
public final class MarkdownSkillLoader {

    private static final String DELIMITER = "---";
    private static final int MAX_SCAN_DEPTH = 4;

    private MarkdownSkillLoader() {
    }

    /**
     * 解析技能文件。
     *
     * @param markdown    文件内容
     * @param fallbackId  frontmatter 缺 {@code name} 时使用的标识（通常取目录名）
     * @param source      来源路径，仅用于日志与诊断
     * @return 解析成功则非空；无 frontmatter、缺 description 或 YAML 非法时为空
     */
    public static Optional<MarkdownSkill> parse(String markdown, String fallbackId, String source) {
        if (markdown == null || markdown.isBlank()) {
            log.debug("技能文件为空: {}", source);
            return Optional.empty();
        }
        Map<String, Object> frontmatter = frontmatter(markdown);
        if (frontmatter == null) {
            log.warn("技能文件缺少 YAML frontmatter，跳过: {}", source);
            return Optional.empty();
        }
        String description = stringOf(frontmatter, "description");
        if (description == null) {
            log.warn("技能文件缺少 description，无法进目录，跳过: {}", source);
            return Optional.empty();
        }
        String id = firstNonBlank(
                stringOf(frontmatter, "name"),
                stringOf(frontmatter, "id"),
                fallbackId);
        if (id == null) {
            log.warn("技能文件既无 name 也无可用回退标识，跳过: {}", source);
            return Optional.empty();
        }
        return Optional.of(new MarkdownSkill(
                id,
                description,
                stringsOf(frontmatter, "triggers", "trigger", "when-to-use", "when_to_use"),
                stringsOf(frontmatter, "tool-names", "tool_names", "allowed-tools", "allowed_tools", "tools"),
                body(markdown),
                source));
    }

    /** 目录扫描深度上限：技能目录层级固定，没必要深挖整个文件系统 */
    public static int maxScanDepth() {
        return MAX_SCAN_DEPTH;
    }

    /**
     * 取出 frontmatter；没有则返回 {@code null}。<br>
     * 只认开头的 {@code ---}：正文中出现的 {@code ---}（分隔线）不应被误判。
     */
    private static Map<String, Object> frontmatter(String markdown) {
        String text = markdown.startsWith("\uFEFF") ? markdown.substring(1) : markdown;
        String[] lines = text.split("\r?\n", -1);
        if (lines.length == 0 || !DELIMITER.equals(lines[0].trim())) {
            return null;
        }
        StringBuilder yaml = new StringBuilder();
        for (int i = 1; i < lines.length; i++) {
            if (DELIMITER.equals(lines[i].trim())) {
                try {
                    return YamlUtil.getYAML_MAPPER()
                            .readValue(yaml.toString(), SomeTypeReference.MAP_STRING_OBJECT);
                } catch (Exception e) {
                    log.warn("技能 frontmatter 不是合法 YAML: {}", e.getMessage());
                    return null;
                }
            }
            yaml.append(lines[i]).append('\n');
        }
        return null;   // 只有开头没有收尾，视为缺失
    }

    /** 正文：frontmatter 之后的全部内容（保留原样，不做任何改写） */
    private static String body(String markdown) {
        String[] lines = markdown.split("\r?\n", -1);
        int start = -1;
        for (int i = 1; i < lines.length; i++) {
            if (DELIMITER.equals(lines[i].trim())) {
                start = i + 1;
                break;
            }
        }
        if (start < 0) {
            return markdown;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString().strip();
    }

    private static String stringOf(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof String s && !s.isBlank()) {
                return s.strip();
            }
        }
        return null;
    }

    /** 同义字段逐个尝试，取第一个命中的非空列表 */
    private static List<String> stringsOf(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            List<String> parsed = toStrings(value);
            if (!parsed.isEmpty()) {
                return parsed;
            }
        }
        return List.of();
    }

    private static List<String> toStrings(Object value) {
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (item != null && !item.toString().isBlank()) {
                    out.add(item.toString().strip());
                }
            }
            return List.copyOf(out);
        }
        if (value instanceof String s && !s.isBlank()) {
            // 单个值也接受：写 `tools: read_file` 是常见简写
            return List.of(s.strip());
        }
        return List.of();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
