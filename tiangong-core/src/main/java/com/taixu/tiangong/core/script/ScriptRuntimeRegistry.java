package com.taixu.tiangong.core.script;

import com.taixu.tiangong.core.skill.SkillRegistry;
import lombok.extern.slf4j.Slf4j;
import com.taixu.tiangong.annotations.annotation.definition.BeanDefinition;
import com.taixu.tiangong.annotations.processor.registry.BeanDefinitionRegistry;
import com.taixu.tiangong.common.exception.SystemRuntimeException;
import com.taixu.tiangong.core.container.Container;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 脚本运行时注册表：按文件后缀找到能执行它的运行时。<br>
 * <br>
 * 装配方式与 {@link SkillRegistry} 完全一致
 * （refresh 之前创建并注册 → refresh 之后收集 Bean 形态实现），原因也相同：
 * {@code @AutoConfiguration} 在 refresh 前执行，要给它留出注册窗口。<br>
 * <br>
 *
 * <h2>找不到运行时时明确报错</h2>
 * 这是本类的核心职责。技能正文写着「运行 {@code scripts/build.ts}」而环境里没有
 * Node —— 此时<b>静默跳过</b>是最糟的结果：模型会以为脚本已经跑过了。
 * {@link #require} 因此直接抛异常，把「缺工具链」这件事暴露在明处。
 *
 * <h2>同一后缀多个运行时</h2>
 * 先注册者优先，后来者只 WARN 不覆盖 —— 覆盖会让「实际生效的是哪个」取决于
 * 扫描顺序，难以复现。
 */
@Slf4j
public class ScriptRuntimeRegistry {

    /** 注册进容器时的 Bean 名 */
    public static final String BEAN_NAME = "scriptRuntimeRegistry";

    private final Map<String, ScriptRuntime> byExtension = new ConcurrentHashMap<>();
    private final List<ScriptRuntime> all = new ArrayList<>();

    /**
     * 注册一个运行时：把它支持且尚无人认领的后缀接管过来。
     */
    public void register(ScriptRuntime runtime) {
        all.add(runtime);
        for (String ext : runtime.supportedExtensions()) {
            ScriptRuntime previous = byExtension.putIfAbsent(normalize(ext), runtime);
            if (previous != null) {
                log.warn("脚本后缀 [{}] 已由 {} 接管，{} 不会被使用",
                        ext, previous.name(), runtime.name());
            } else {
                log.debug("脚本运行时 {} 接管后缀 [{}]", runtime.name(), ext);
            }
        }
    }

    /** 按后缀查找；没有可用运行时时为空 */
    public Optional<ScriptRuntime> resolve(String extension) {
        return Optional.ofNullable(byExtension.get(normalize(extension)));
    }

    /**
     * 按后缀取得运行时，<b>取不到就抛异常</b>。<br>
     * 用于「技能要求执行某脚本」这类必须给明确交代的场景。
     *
     * @throws SystemRuntimeException 没有能执行该后缀的运行时
     */
    public ScriptRuntime require(String extension) {
        return resolve(extension).orElseThrow(() -> new SystemRuntimeException(
                // 括号不能省：字符串拼接后再 .formatted() 只会作用于最后一段字面量，
                // %s 会以原样出现在错误信息里（实测踩到过）
                ("没有能执行 [.%s] 脚本的运行时：环境可能缺少对应工具链，"
                        + "或适配层未注册 ScriptRuntime Bean（如 tiangong-skill 的 NodeScriptRuntime）")
                        .formatted(normalize(extension))));
    }

    /** 全部已注册的运行时（诊断用） */
    public Collection<ScriptRuntime> values() {
        return List.copyOf(all);
    }

    public int size() {
        return all.size();
    }

    /**
     * 收集 Container 里 Bean 形态的运行时，refresh 之后调用。
     */
    public void loadBeans(BeanDefinitionRegistry registry, Container container) {
        for (BeanDefinition def : registry) {
            Class<?> type = def.getBeanClass();
            if (!ScriptRuntime.class.isAssignableFrom(type)) {
                continue;
            }
            if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
                continue;
            }
            Object bean = container.getBean(def.getBeanName(), type);
            if (bean instanceof ScriptRuntime runtime && runtime.supportedExtensions() != null) {
                register(runtime);
            }
        }
        log.info("脚本运行时装配完成：{} 个", size());
    }

    /** 后缀统一成小写、去掉前导点 */
    private static String normalize(String extension) {
        if (extension == null) {
            return "";
        }
        String ext = extension.trim();
        if (ext.startsWith(".")) {
            ext = ext.substring(1);
        }
        return ext.toLowerCase();
    }
}
