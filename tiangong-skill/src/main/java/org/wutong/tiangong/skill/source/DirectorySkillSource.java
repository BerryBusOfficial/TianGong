package org.wutong.tiangong.skill.source;

import lombok.extern.slf4j.Slf4j;
import org.wutong.tiangong.core.skill.Skill;
import org.wutong.tiangong.skill.loader.MarkdownSkillLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 目录式技能源：递归扫描目录下所有 {@code SKILL.md}。<br>
 * <br>
 * 对应「别人写好的技能包直接放进目录」这一最常见的用法 —— 这也是适配生态时
 * 投入产出比最高的一层：<b>纯文本解析，零外部依赖</b>。<br>
 * <br>
 * <h2>容错原则</h2>
 * 单个文件解析失败<b>不影响其余</b>：一个坏文件不该让整批技能失效。
 * 这与框架既有的「关闭 / 清理类操作逐个容错」一致。
 */
@Slf4j
public final class DirectorySkillSource {

    private static final String FILE_NAME = "SKILL.md";

    private DirectorySkillSource() {
    }

    /**
     * 扫描目录。
     *
     * @param root 根目录；不存在或不是目录时返回空列表
     * @return 解析成功的技能，顺序按文件遍历顺序
     */
    public static List<Skill> load(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            log.debug("技能目录不存在，跳过: {}", root);
            return List.of();
        }
        List<Skill> skills = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root, MarkdownSkillLoader.maxScanDepth())) {
            List<Path> files = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase(FILE_NAME))
                    .toList();
            for (Path file : files) {
                parse(file).ifPresent(skills::add);
            }
        } catch (IOException e) {
            log.warn("扫描技能目录失败 [{}]: {}", root, e.getMessage());
        }
        log.info("技能目录 [{}] 载入 {} 个技能", root, skills.size());
        return List.copyOf(skills);
    }

    private static Optional<Skill> parse(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String fallbackId = file.getParent().getFileName().toString();
            return MarkdownSkillLoader.parse(content, fallbackId, file.toString())
                    .map(Skill.class::cast);
        } catch (IOException e) {
            log.warn("读取技能文件失败 [{}]: {}", file, e.getMessage());
            return Optional.empty();
        }
    }
}
