package org.wutong.tiangong.spring.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.wutong.tiangong.common.exception.SystemException;
import org.wutong.tiangong.common.resource.ResourceLoader;
import org.wutong.tiangong.common.util.YamlUtil;
import org.wutong.tiangong.core.config.ConfigLoader;
import org.wutong.tiangong.spring.TianGongProperties;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置桥：把 Spring 世界的配置装载给天工的 {@code ConfigHolder}。<br>
 * <br>
 * 合成顺序（后者覆盖前者）：
 * <ol>
 *     <li><b>文件层</b>：{@code tiangong.config} 指定的文件（或隐式探测 classpath:agent.yaml）
 *         —— 交给天工原生 YAML 解析（与单机语义一致），用于低迁移成本过渡；</li>
 *     <li><b>Spring 覆盖层</b>：{@code Environment} 中白名单命名空间
 *         （默认 {@code agent/context/llm/pet/tiangong}）的值 ——
 *         <b>application.yml 为准</b>，与文件层<b>同构</b>迁移（顶层键不变，{@code @Value} 无需改）。</li>
 * </ol>
 * <br>
 * 为什么用 {@link Binder} 而不是手工遍历 PropertySource：YAML 列表在 Environment 里是
 * 索引键（{@code agent.models[0].name}），手工拼树要自己处理索引与嵌套；Binder 直接给出
 * 与文件层同构的 Map/List 结构。
 */
@Slf4j
public final class SpringConfigBridge {

    private SpringConfigBridge() {
    }

    /**
     * 装载配置：文件层 + Spring 覆盖层 → {@link ConfigLoader#loadFrom}。
     *
     * @param appClass 启动类（决定 ClassLoader，供资源定位）
     * @throws SystemException 显式指定的配置文件不存在或解析失败
     */
    public static void apply(Class<?> appClass, Environment environment, TianGongProperties props)
            throws SystemException {
        ResourceLoader.load(appClass);

        Loaded base = loadBase(props.getConfig());
        Map<String, Object> overlay = overlay(environment, props.getConfigNamespaces());
        Map<String, Object> merged = deepMerge(base.data(), overlay);
        ConfigLoader.loadFrom(merged);

        log.info("TianGong 配置已装载：文件层={}（{} 个顶层键），Spring 覆盖层命名空间={}",
                base.source() == null ? "（无）" : base.source(), base.data().size(),
                props.getConfigNamespaces());
    }

    /** 文件层的装载结果：内容 + 实际使用的路径（诊断用）。 */
    private record Loaded(Map<String, Object> data, String source) {
    }

    // ==================== 文件层 ====================

    private static Loaded loadBase(String config) throws SystemException {
        String path = config;
        boolean explicit = path != null && !path.isBlank();
        if (!explicit) {
            // 隐式探测：与 ConfigLoader 保持<b>同一文件名顺序</b>（agent.yml 先于 agent.yaml）。
            // 顺序就是契约：依赖的 classpath 里若混入 agent.yaml，先查 .yaml 会把应用自己的
            // agent.yml 顶掉 —— 实测踩过（tiangong-core 误提交的本地配置劫持了 CodeReview）。
            for (String candidate : new String[]{"agent.yml", "agent.yaml"}) {
                if (ResourceLoader.exists(candidate)) {
                    path = candidate;
                    break;
                }
            }
            if (path == null) {
                return new Loaded(Map.of(), null);
            }
        } else if (path.startsWith("classpath:")) {
            path = path.substring("classpath:".length());
        }

        if (!ResourceLoader.exists(path)) {
            throw new SystemException("tiangong.config 指定的配置文件不存在: " + config);
        }
        try {
            Map<String, Object> loaded = YamlUtil.loadYaml(path);
            return new Loaded(loaded == null ? Map.of() : loaded, path);
        } catch (IOException e) {
            throw new SystemException("加载 TianGong 配置文件失败: " + path, e);
        }
    }

    // ==================== Spring 覆盖层 ====================

    private static Map<String, Object> overlay(Environment environment, List<String> namespaces) {
        Binder binder = Binder.get(environment);
        Map<String, Object> overlay = new LinkedHashMap<>();
        for (String namespace : namespaces) {
            try {
                binder.bind(namespace, Bindable.mapOf(String.class, Object.class))
                        .ifBound(value -> overlay.put(namespace, value));
            } catch (Exception e) {
                // 环境里可能有无关键的标量同名变量（实测：测试机的 AGENT=1 / CONTEXT=... ），
                // 它会让"命名空间 → Map"的绑定整体失败。此时跳过该命名空间并明确告警：
                // 环境变量撞名不该让应用起不来；真的想用该命名空间，请把标量键改名（AGENT=1 → 去掉）。
                log.warn("配置覆盖层跳过命名空间 [{}]：绑定失败（可能是环境变量撞名，如 {} 被设置为标量）: {}",
                        namespace, namespace.toUpperCase(), e.getMessage());
            }
        }
        return overlay;
    }

    // ==================== 深合并 ====================

    @SuppressWarnings("unchecked")
    static Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> overlay) {
        Map<String, Object> merged = new LinkedHashMap<>(base);
        overlay.forEach((key, value) -> {
            Object existing = merged.get(key);
            if (existing instanceof Map && value instanceof Map) {
                merged.put(key, deepMerge((Map<String, Object>) existing, (Map<String, Object>) value));
            } else {
                merged.put(key, value);
            }
        });
        return merged;
    }
}
