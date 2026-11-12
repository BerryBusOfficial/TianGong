package org.wutong.tiangong.skill.tool;

import org.wutong.tiangong.core.function.AgentFunctionDefinition;
import org.wutong.tiangong.core.function.FunctionRegistry;
import org.wutong.tiangong.core.skill.Skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 宿主工具映射（L2 适配层）：把技能正文里写的<b>生态工具名</b>解析成宿主真实注册的工具。<br>
 * <br>
 * <h2>为什么必须有这一层</h2>
 * 主流技能生态（如 Claude 系 SKILL.md）在 frontmatter 里写的是它自己那套工具名：
 * {@code Bash} / {@code Read} / {@code Write} / {@code Grep} / {@code Glob} / {@code Edit}。
 * 宿主的命名千差万别 —— 本框架的示例应用就叫 {@code sys_read}。<br>
 * 若只做「按名字精确查找」，技能声明的工具与宿主提供的工具<b>永远对不上</b>：
 * 技能看着完整，模型一调用就是「没有这个工具」。这一层就是把两者接起来。<br>
 * <br>
 *
 * <h2>解析顺序</h2>
 * <ol>
 *     <li><b>精确匹配</b>：宿主直接用生态名注册（本模块的 {@link ShellTool} 就是这么做的）时命中</li>
 *     <li><b>别名回退</b>：按 {@link #ALIASES} 逐个候选名查找</li>
 * </ol>
 * 顺序不能倒：别名表是「猜」，精确匹配是「事实」，事实优先。<br>
 * <br>
 *
 * <h2>为什么不做双向映射 / 不做翻译</h2>
 * 工具的参数 schema 由宿主决定，改名不等于可用。这里只做<b>名字解析</b>，
 * 参数照宿主的定义走。把 {@code Read} 翻译成 {@code sys_read} 之后再替模型填
 * {@code offset}/{@code limit} 是过度设计，且必然出错。<br>
 * <br>
 *
 * <h2>缺失怎么办：报出来，不抛异常</h2>
 * {@link #audit} 返回哪些解析到了、哪些没解析到。缺失是配置问题、属正常业务结果
 * （与「沙箱 / 超时 / 取消不抛异常」同一立场）。报出来的用途是<b>告诉模型别去调</b> ——
 * 见 {@link Report#footer()}：正文末尾附一句，比让模型试三次失败三次划算得多。
 */
public final class HostToolMapping {

    /**
     * 生态工具名 → 宿主可能使用的名字（按优先级排列）。<br>
     * <br>
     * 刻意<b>不做完备</b>：这张表只覆盖主流生态里高频出现的几个名字，
     * 且大小写不敏感匹配。宿主若有更特殊的命名，应自行注册生态同名工具
     * （或提供自己的映射），而不是等框架把全世界的方言都收进来。
     */
    public static final Map<String, List<String>> ALIASES = Map.ofEntries(
            Map.entry("bash", List.of("Bash", "shell", "shell_exec", "execute_command", "run_command", "terminal")),
            Map.entry("read", List.of("Read", "sys_read", "read_file", "readFile", "file_read")),
            Map.entry("write", List.of("Write", "write_file", "writeFile", "file_write", "create_file")),
            Map.entry("edit", List.of("Edit", "edit_file", "replace_in_file", "str_replace")),
            Map.entry("glob", List.of("Glob", "list_files", "find_files", "ls")),
            Map.entry("grep", List.of("Grep", "search", "search_content", "grep_files"))
    );

    private HostToolMapping() {
    }

    /**
     * 解析单个工具名。
     *
     * @return 命中的工具定义；名字为空、注册表为空、精确与别名都没命中时为空
     */
    public static Optional<AgentFunctionDefinition> resolve(String declared, FunctionRegistry registry) {
        if (declared == null || declared.isBlank() || registry == null) {
            return Optional.empty();
        }
        Optional<AgentFunctionDefinition> exact = registry.get(declared);
        if (exact.isPresent()) {
            return exact;
        }
        List<String> candidates = ALIASES.getOrDefault(
                declared.toLowerCase(Locale.ROOT), List.of());
        for (String candidate : candidates) {
            Optional<AgentFunctionDefinition> hit = registry.get(candidate);
            if (hit.isPresent()) {
                return hit;
            }
        }
        return Optional.empty();
    }

    /**
     * 解析某技能声明的全部工具 —— L2 的实际产出。<br>
     * 与 {@code SkillRegistry#functions} 的区别：后者只做精确匹配，这里多一层别名回退。
     *
     * @return 已解析的工具定义，顺序与声明一致；未命中的跳过
     */
    public static List<AgentFunctionDefinition> resolveAll(Skill skill, FunctionRegistry registry) {
        List<AgentFunctionDefinition> resolved = new ArrayList<>();
        if (skill == null || registry == null || skill.toolNames() == null) {
            return resolved;
        }
        for (String name : skill.toolNames()) {
            resolve(name, registry).ifPresent(resolved::add);
        }
        return List.copyOf(resolved);
    }

    /**
     * 审计某技能的工具供给情况。<br>
     * 没声明工具的技能返回空报告（{@link Report#empty()} 为 true）—— 不是问题，无需告警。
     */
    public static Report audit(Skill skill, FunctionRegistry registry) {
        if (skill == null || skill.toolNames() == null || skill.toolNames().isEmpty()) {
            return new Report(List.of(), List.of());
        }
        Map<String, Boolean> hit = new LinkedHashMap<>();
        for (String name : skill.toolNames()) {
            hit.put(name, resolve(name, registry).isPresent());
        }
        List<String> resolved = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        hit.forEach((name, ok) -> (ok ? resolved : missing).add(name));
        return new Report(List.copyOf(resolved), List.copyOf(missing));
    }

    /**
     * 工具供给审计结果。
     *
     * @param resolved 已解析到的工具名（按声明顺序）
     * @param missing  宿主未提供的工具名
     */
    public record Report(List<String> resolved, List<String> missing) {

        /** 是否有需要报告的内容：技能声明过工具且存在缺失 */
        public boolean hasMissing() {
            return !missing.isEmpty();
        }

        /** 是否声明过任何工具 */
        public boolean empty() {
            return resolved.isEmpty() && missing.isEmpty();
        }

        /**
         * 追加到技能正文末尾的提示。<br>
         * 直接告诉模型「别调」，而不是让它自己撞墙 ——
         * 模型调一个不存在的工具，代价是一整轮往返加一次失败的推理。
         */
        public String footer() {
            if (!hasMissing()) {
                return "";
            }
            return "\n\n[宿主提示] 以下工具在当前环境不可用，不要尝试调用："
                    + String.join("、", missing)
                    + "。可用替代方案或跳过相应步骤。";
        }
    }
}
