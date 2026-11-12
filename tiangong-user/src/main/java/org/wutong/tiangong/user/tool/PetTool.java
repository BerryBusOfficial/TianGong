package org.wutong.tiangong.user.tool;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.wutong.tiangong.annotations.annotation.framework.agent.AgentFunction;
import org.wutong.tiangong.annotations.annotation.framework.agent.AgentTool;
import org.wutong.tiangong.annotations.annotation.framework.agent.ToolNames;
import org.wutong.tiangong.annotations.annotation.framework.inject.Inject;
import org.wutong.tiangong.user.memory.PetMemory;
import org.wutong.tiangong.user.model.Mood;

import java.util.Arrays;
import java.util.Locale;

/**
 * 桌宠自己的工具：让模型能<b>主动</b>读写记忆与切换心情。<br>
 * <br>
 *
 * <h2>为什么记忆要靠工具而不是自动提炼</h2>
 * 「用户说了什么值得记住」这件事，模型比规则判断得准；而自动提炼（每轮额外一次 LLM 调用）
 * 的代价是：提炼错了会写入一条<b>错误事实</b>，而错误事实会被一直当真、反复引用，
 * 比「没记住」更糟。交给模型显式调用，至少每次写入都有一个可追溯的决策点。
 *
 * <h2>为什么工具自己注入 {@link PetMemory} 而不是层层传参</h2>
 * 工具是容器 Bean，注入是框架原生能力。若改成从上下文里取，就得让上下文暴露记忆对象，
 * 等于把「记忆」这个内部实现细节变成上下文的公共 API。
 *
 * <h2>工具名常量从哪来</h2>
 * 这里<b>不手写</b> {@code REMEMBER_FACT} 那类常量 —— 标了 {@link ToolNames}，编译期会生成
 * {@link PetToolNames}，里面就是 {@code REMEMBER_FACT / FORGET_FACT / RECALL_FACTS / SET_MOOD}。
 * 值取 {@code @AgentFunction} 的 {@code name}，和运行期注册进 FunctionRegistry 的名字同源，
 * 不会出现「常量改了、注册名没改」的错位。
 */
@Slf4j
@AgentTool
@ToolNames
public class PetTool {

    /** setter 只给测试装配用；运行期由容器注入 */
    @Inject
    @Setter
    private PetMemory memory;

    @AgentFunction(
            name = "remember_fact",
            description = "记住一条关于用户的事实（称呼、偏好、习惯、在意的事、重要日期）。同一个 key 会被覆盖，所以 key 要稳定。"
    )
    public String rememberFact(
            @AgentFunction.ToolParam(name = "key", description = "事实的简短名字，如：称呼 / 喜欢的饮料 / 职业") String key,
            @AgentFunction.ToolParam(name = "value", description = "事实内容，简短具体") String value
    ) {
        if (key == null || key.isBlank()) {
            return "没记住：key 不能为空";
        }
        if (value == null || value.isBlank()) {
            return "没记住：value 不能为空";
        }
        memory.remember(key, value);
        return "记住啦：%s = %s".formatted(key.trim(), value.trim());
    }

    @AgentFunction(
            name = "forget_fact",
            description = "忘掉一条关于用户的事实。用户说「别记了」「我说错了」时用。"
    )
    public String forgetFact(
            @AgentFunction.ToolParam(name = "key", description = "要忘掉的事实的名字") String key
    ) {
        String removed = memory.forget(key);
        return removed == null
                ? "本来就没记过「%s」".formatted(key)
                : "忘掉啦：%s（原值：%s）".formatted(key, removed);
    }

    @AgentFunction(
            name = "recall_facts",
            description = "列出你记得的关于用户的所有事实。回答「你还记得我什么」时用。"
    )
    public String recallFacts() {
        String text = memory.recallText();
        return text.isEmpty() ? "目前还没记住关于你的事。" : text;
    }

    @AgentFunction(
            name = "set_mood",
            description = "切换自己的心情。被夸奖、被逗乐、被冷落时主动切换，别一直一个表情。"
    )
    public String setMood(
            @AgentFunction.ToolParam(name = "mood", description = "心情：开心/兴奋/平静/好奇/困倦/闹别扭") String mood
    ) {
        Mood parsed = parseMood(mood);
        if (parsed == null) {
            return "没有这个心情呢，可选：" + Arrays.stream(Mood.values())
                    .map(Mood::label)
                    .reduce((a, b) -> a + "/" + b)
                    .orElse("");
        }
        memory.setMood(parsed);
        return "心情切到「%s」——%s".formatted(parsed.label(), parsed.actingNote());
    }

    /** 中文名与枚举名都收：模型两种都可能输出 */
    static Mood parseMood(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        for (Mood mood : Mood.values()) {
            if (mood.label().equals(text)) {
                return mood;
            }
        }
        String upper = text.toUpperCase(Locale.ROOT);
        for (Mood mood : Mood.values()) {
            if (mood.name().equals(upper)) {
                return mood;
            }
        }
        return null;
    }
}
