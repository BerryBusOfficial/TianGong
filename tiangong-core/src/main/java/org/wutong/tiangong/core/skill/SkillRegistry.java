package org.wutong.tiangong.core.skill;

import lombok.extern.slf4j.Slf4j;
import org.wutong.tiangong.annotations.annotation.definition.BeanDefinition;
import org.wutong.tiangong.annotations.processor.registry.BeanDefinitionRegistry;
import org.wutong.tiangong.common.exception.SystemRuntimeException;
import org.wutong.tiangong.core.container.Container;
import org.wutong.tiangong.core.function.AgentFunctionDefinition;
import org.wutong.tiangong.core.function.FunctionRegistry;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 技能注册表：持有全部 {@link Skill}，并提供渐进式披露所需的三个出口。<br>
 * <br>
 * <h2>三个出口</h2>
 * <ul>
 *     <li>{@link #catalog()} —— 目录文本（id + 一句话描述），常驻系统提示词</li>
 *     <li>{@link #body(String)} —— 命中后取正文</li>
 *     <li>{@link #functions(String, FunctionRegistry)} —— 命中后解析出该技能依赖的工具</li>
 * </ul>
 *
 * <h2>为什么「先建后填」</h2>
 * 注册表实例在容器 <b>refresh 之前</b>就创建并注册进容器，技能的收集分两批：
 * <ul>
 *     <li><b>refresh 之前</b>：{@code @AutoConfiguration} 在此阶段执行
 *         （见 {@code ApplicationFrameworkContext#enableConfigure}），
 *         {@code tiangong-skill} 这类模块要在这个时机把目录式技能注册进来</li>
 *     <li><b>refresh 之后</b>：{@link #loadBeans} 收集 Bean 形态的技能 ——
 *         那时 Bean 才可解析（提前取会得到 {@code @Inject} 字段为 null 的半成品）</li>
 * </ul>
 * 之所以能提前注册：{@code Container.register} 直写单例缓存，而 {@code refresh()}
 * 只补建 Bean 定义、<b>不清空</b>已有实例。
 *
 * <h2>工具解析为什么现查现用</h2>
 * MCP 工具的连接建立晚于容器 refresh，若装配时就把工具名解析并缓存下来，
 * 很可能得到空集且<b>永久错误</b>。改为每次调用时查询 {@code FunctionRegistry}，
 * 代价是一次 map 查找，换来注册顺序无关。
 *
 * <h2>工具找不到的处理</h2>
 * {@code WARN + 跳过}，<b>不抛异常</b>。技能声明的工具没注册属配置问题、是正常业务结果，
 * 与「沙箱 / 超时 / 取消不抛异常」同一立场；只有技能 id 重复（会让人分不清用的是哪个）
 * 才视为故障。
 */
@Slf4j
public class SkillRegistry {

    /** 注册进容器时的 Bean 名，供应用 {@code @Inject} 或按名取用 */
    public static final String BEAN_NAME = "skillRegistry";

    private final Map<String, Skill> byId = new ConcurrentHashMap<>();

    /**
     * 注册一个技能。
     *
     * @throws SystemRuntimeException id 重复
     */
    public void register(Skill skill) {
        Skill previous = byId.putIfAbsent(skill.id(), skill);
        if (previous != null) {
            throw new SystemRuntimeException(
                    "重复注册技能: [%s] 已绑定 %s，本次尝试绑定 %s".formatted(
                            skill.id(),
                            previous.getClass().getName(),
                            skill.getClass().getName()));
        }
        log.debug("注册技能: {} -> {}", skill.id(), skill.getClass().getSimpleName());
    }

    /** 按 id 查找；未注册时为空 */
    public Optional<Skill> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** 正文；技能不存在时为空 */
    public Optional<String> body(String id) {
        return find(id).map(Skill::body);
    }

    /** 全部已注册技能 */
    public Collection<Skill> values() {
        return byId.values();
    }

    public int size() {
        return byId.size();
    }

    /**
     * 目录文本：每个技能一行「id — 描述」，带触发词的附在括号内。<br>
     * <br>
     * 刻意保持<b>极简</b>：目录常驻系统提示词，每多一个字都在占用每轮都要付出的
     * 上下文预算。正文不在这里出现。
     */
    public String catalog() {
        if (byId.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("可用技能：\n");
        for (Skill skill : byId.values()) {
            sb.append("- ").append(skill.id()).append(": ").append(skill.description());
            if (skill.triggers() != null && !skill.triggers().isEmpty()) {
                sb.append("（适用：").append(String.join("、", skill.triggers())).append("）");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * 解析某技能声明的工具。<b>每次现查</b>，不缓存。<br>
     * 未注册的工具名跳过并告警 —— 不因此让整个技能不可用。
     *
     * @return 已解析的工具定义，顺序与声明一致
     */
    public List<AgentFunctionDefinition> functions(String id, FunctionRegistry functionRegistry) {
        Skill skill = byId.get(id);
        if (skill == null) {
            log.warn("未注册的技能: [{}]，工具解析返回空集", id);
            return List.of();
        }
        if (skill.toolNames() == null || skill.toolNames().isEmpty()) {
            return List.of();
        }
        List<AgentFunctionDefinition> resolved = new ArrayList<>();
        for (String name : skill.toolNames()) {
            Optional<AgentFunctionDefinition> def = functionRegistry.get(name);
            if (def.isEmpty()) {
                log.warn("技能 [{}] 声明的工具 [{}] 未注册，已跳过", id, name);
                continue;
            }
            resolved.add(def.get());
        }
        return List.copyOf(resolved);
    }

    /**
     * 收集<b>Bean 形态</b>的技能：扫描全部 Bean 定义，把 {@link Skill} 实现类注册进来。<br>
     * <br>
     * 必须在 {@code container.refresh()} <b>之后</b>调用。<br>
     * <br>
     * 之所以遍历 {@code BeanDefinitionRegistry} 而不是 {@code container.getBean(Skill.class)}：
     * 后者只返回<b>一个</b>候选（{@code @Primary} 优先），会漏掉其余技能。
     * {@code Container} 没有 {@code getBeansOfType}，只能按定义逐个取。
     */
    public void loadBeans(BeanDefinitionRegistry registry, Container container) {
        for (BeanDefinition def : registry) {
            Class<?> type = def.getBeanClass();
            if (!Skill.class.isAssignableFrom(type)) {
                continue;
            }
            // 接口与抽象类无法实例化
            if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
                continue;
            }
            Object bean = container.getBean(def.getBeanName(), type);
            if (bean instanceof Skill skill) {
                register(skill);
            }
        }
        log.info("技能装配完成：Bean 形态 {} 个，累计 {} 个",
                countBeanSkills(registry), size());
    }

    private static long countBeanSkills(BeanDefinitionRegistry registry) {
        long n = 0;
        for (BeanDefinition def : registry) {
            Class<?> type = def.getBeanClass();
            if (Skill.class.isAssignableFrom(type)
                    && !type.isInterface()
                    && !Modifier.isAbstract(type.getModifiers())) {
                n++;
            }
        }
        return n;
    }
}
