package com.taixu.tiangong.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.taixu.tiangong.core.skill.Skill;
import com.taixu.tiangong.skill.loader.MarkdownSkillLoader;
import com.taixu.tiangong.skill.source.DirectorySkillSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code SKILL.md} 解析验证。<br>
 * <br>
 * 这些用例锁的是「生态适配」这一层的行为：字段别名、容错、正文保真。
 * 它们一旦退化，表现为技能<b>静默消失</b>或目录里出现空描述，很难从运行日志反查。
 */
class MarkdownSkillLoaderTest {

    private static final String STANDARD = """
            ---
            name: pdf-extract
            description: 从 PDF 中抽取文本与表格
            ---
            ## 步骤
            1. 读取文件
            2. 输出 Markdown
            """;

    @Test
    @DisplayName("解析标准 SKILL.md：frontmatter 进元数据，其余原样作正文")
    void parsesStandardFile() {
        MarkdownSkill skill = MarkdownSkillLoader.parse(STANDARD, "fallback", "test").orElseThrow();

        assertEquals("pdf-extract", skill.id());
        assertEquals("从 PDF 中抽取文本与表格", skill.description());
        assertTrue(skill.body().contains("## 步骤"), "正文应保留 Markdown 结构");
        assertFalse(skill.body().contains("---"), "正文不应残留 frontmatter 分隔符");
    }

    @Test
    @DisplayName("字段别名：allowed-tools 映射为依赖工具（生态里写法不统一）")
    void mapsToolAliases() {
        String md = """
                ---
                name: file-ops
                description: 文件读写操作
                allowed-tools:
                  - read_file
                  - write_file
                ---
                正文
                """;
        MarkdownSkill skill = MarkdownSkillLoader.parse(md, "fallback", "test").orElseThrow();

        assertEquals(List.of("read_file", "write_file"), skill.toolNames());
    }

    @Test
    @DisplayName("缺 name 时回退为目录名，不至于让技能消失")
    void fallsBackToDirectoryName() {
        String md = """
                ---
                description: 没有名字的技能
                ---
                正文
                """;
        MarkdownSkill skill = MarkdownSkillLoader.parse(md, "my-dir", "test").orElseThrow();

        assertEquals("my-dir", skill.id());
    }

    @Test
    @DisplayName("缺 description 时跳过：没有描述的技能无法进目录")
    void skipsWhenDescriptionMissing() {
        String md = """
                ---
                name: broken
                ---
                正文
                """;
        assertTrue(MarkdownSkillLoader.parse(md, "fallback", "test").isEmpty());
    }

    @Test
    @DisplayName("无 frontmatter 时跳过，且不以正文首行冒充描述")
    void skipsWhenFrontmatterMissing() {
        assertTrue(MarkdownSkillLoader.parse("只是一些说明文字", "fallback", "test").isEmpty());
    }

    @Test
    @DisplayName("目录扫描：多个技能目录一次载入，坏文件不影响其余")
    void scansDirectory(@TempDir Path root) throws IOException {
        write(root.resolve("alpha/SKILL.md"), """
                ---
                name: alpha
                description: 第一个技能
                ---
                正文 A
                """);
        write(root.resolve("beta/SKILL.md"), """
                ---
                name: beta
                description: 第二个技能
                ---
                正文 B
                """);
        write(root.resolve("broken/SKILL.md"), "缺少 frontmatter 的文件");

        List<Skill> skills = DirectorySkillSource.load(root);

        assertEquals(2, skills.size(), "坏文件应被跳过，其余照常载入");
        assertTrue(skills.stream().anyMatch(s -> s.id().equals("alpha")));
        assertTrue(skills.stream().anyMatch(s -> s.id().equals("beta")));
    }

    @Test
    @DisplayName("目录不存在时返回空，不抛异常")
    void missingDirectoryIsEmpty(@TempDir Path root) {
        assertTrue(DirectorySkillSource.load(root.resolve("not-exist")).isEmpty());
    }

    @Test
    @DisplayName("frontmatter 非法 YAML 时跳过，而不是抛出")
    void skipsOnInvalidYaml() {
        String md = """
                ---
                name: [unclosed
                description: 坏的
                ---
                正文
                """;
        Optional<MarkdownSkill> parsed = MarkdownSkillLoader.parse(md, "fallback", "test");

        assertTrue(parsed.isEmpty(), "非法 YAML 应被吞掉并告警，不能让整批技能加载失败");
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
