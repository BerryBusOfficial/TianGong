package org.wutong.tiangong.core.skill;

import java.util.List;

/**
 * 技能（Skill）：一段<b>按需加载</b>的能力描述。<br>
 * <br>
 * <h2>与工具（Tool）的区别</h2>
 * <ul>
 *     <li><b>Tool</b>（{@code @AgentFunction}）：原子函数调用。全部工具<b>一律</b>进入
 *         LLM 的函数表，成本随工具数量线性增长</li>
 *     <li><b>Skill</b>：能力描述包 —— 一句话描述常驻（目录），完整正文<b>命中后才加载</b>，
 *         并可声明它依赖哪些工具。价值在<b>渐进式披露</b>：工具可以有很多，
 *         但同一时刻暴露给模型的只有当前用得上的那一批</li>
 * </ul>
 * 如果没有这层按需加载，Skill 就只是「一堆 Tool 打个包」，没有增量价值。
 *
 * <h2>为什么是接口而不是注解</h2>
 * 注解只能挂常量，而 {@link #body()} 常常需要动态计算（读文件、查库、按环境拼装）。
 * 用接口还有个直接好处：实现类本身是容器 Bean，可以照常 {@code @Inject} 任何依赖 ——
 * 这是 Java 框架相对「脚本式 Skill」的天然优势，不需要外挂解释器。
 *
 * <h2>框架不代劳的三件事</h2>
 * <ol>
 *     <li><b>不自动把目录塞进系统提示词</b>：系统提示词怎么拼是应用语义
 *         （{@code AgentContext.init()} 决定），框架只提供 {@link SkillRegistry#catalog()}</li>
 *     <li><b>不做命中判定</b>：判定依据可以是模型、可以是规则，框架不猜</li>
 *     <li><b>不重复实现调用</b>：{@link #toolNames()} 只是名字引用，
 *         执行仍归 {@code FunctionRegistry}（本地函数与 MCP 工具都注册在那里）</li>
 * </ol>
 * 这与沙箱那次定下的立场一致：<b>框架只提供接入口，不替应用决定语义</b>。
 */
public interface Skill {

    /**
     * 技能唯一标识。<br>
     * 同一注册表内重复即抛异常 —— 静默覆盖会让「实际生效的是哪一个」无法判断。
     */
    String id();

    /**
     * 一句话描述：进目录，是模型决定是否展开的唯一依据。<br>
     * 写清楚「做什么」与「什么时候该用」，别写实现方式。
     */
    String description();

    /**
     * 触发提示词：仅用于目录展示，<b>框架不做自动匹配</b>。<br>
     * 命中判定交给模型或应用自己的规则 —— 关键词匹配在自然语言中误判率高，
     * 由框架兜底只会得到一个似是而非的结果。
     */
    default List<String> triggers() {
        return List.of();
    }

    /**
     * 本技能依赖的工具名（本地函数 / MCP 工具均可）。<br>
     * 命中后由 {@link SkillRegistry#functions} 解析成真正的
     * {@code AgentFunctionDefinition}，应用据此只把这一批工具交给模型。
     */
    default List<String> toolNames() {
        return List.of();
    }

    /**
     * 完整正文：操作指南 / 约束 / 参考资料。<br>
     * 只在命中后加载 —— 这是渐进式披露的payload，也是成本控制的重点。
     */
    String body();
}
