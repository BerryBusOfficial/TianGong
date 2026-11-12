package com.taixu.tiangong.spring.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.Environment;
import com.taixu.tiangong.common.exception.SystemException;
import com.taixu.tiangong.common.resource.ResourceLoader;
import com.taixu.tiangong.common.util.YamlUtil;
import com.taixu.tiangong.core.config.ConfigLoader;
import com.taixu.tiangong.spring.TianGongProperties;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
        Map<String, Object> overlay = new LinkedHashMap<>();
        for (String namespace : namespaces) {
            Map<String, Object> bound = bindNamespace(environment, namespace);
            if (bound != null && !bound.isEmpty()) {
                overlay.put(namespace, bound);
            }
        }
        return overlay;
    }

    /**
     * 绑定单个命名空间。先用 {@link Binder}；若返回空则回退到「按属性名重建」。<br>
     * <b>为什么需要回退</b>：Binder 绑定到 {@code Map<String, Object>} 时，值为嵌套结构
     * （如 {@code agent.models[0].name}）是否被展开取决于 Boot 版本对 {@code Object} 目标值的
     * 处理——实测 Spring Boot 4.1.2 下绑定成功但结果为空，且不抛异常，于是宿主在
     * application.yaml 里写的 {@code agent.models} 被静默丢弃（天工日志只会说"未配置"）。
     * 重建路径直接读属性名（{@code agent.models[0].name}）并还原成嵌套树，语义与 YAML 同构。
     */
    private static Map<String, Object> bindNamespace(Environment environment, String namespace) {
        // 优先按属性名重建：Boot 4.1.2 下 Binder 绑定到 Map<String, Object> 会返回
        // 「空但非空」的结构（如 {models: []}），它不抛异常，却会在 deepMerge 时
        // 把文件层 agent.yml 的 models 整体盖成空列表 —— 表现为天工报「未配置 agent.models」。
        Map<String, Object> rebuilt = rebuildFromPropertyNames(environment, namespace);
        if (!rebuilt.isEmpty()) {
            log.info("配置覆盖层 [{}] 由属性名重建：{} 个顶层键", namespace, rebuilt.size());
            return rebuilt;
        }
        try {
            Map<String, Object> bound = Binder.get(environment)
                    .bind(namespace, Bindable.mapOf(String.class, Object.class))
                    .orElse(null);
            if (bound != null && !bound.isEmpty()) {
                return bound;
            }
        } catch (Exception e) {
            // 环境里可能有同名标量变量（实测：测试机的 AGENT=1 / CONTEXT=...），它会让绑定整体失败。
            log.warn("配置覆盖层 [{}] Binder 绑定失败：{}", namespace, e.getMessage());
        }
        return Map.of();
    }

    /** 把 {@code agent.models[0].name} 这类扁平属性名还原成嵌套 Map（列表转 List）。 */
    private static Map<String, Object> rebuildFromPropertyNames(Environment environment, String namespace) {
        String prefix = namespace + ".";
        Map<String, Object> flat = new LinkedHashMap<>();
        if (!(environment instanceof org.springframework.core.env.ConfigurableEnvironment configurable)) {
            return Map.of();
        }
        for (org.springframework.core.env.PropertySource<?> source : configurable.getPropertySources()) {
            if (!(source instanceof org.springframework.core.env.EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                if (!name.startsWith(prefix)) {
                    continue;
                }
                String value = environment.getProperty(name);
                if (value != null) {
                    flat.put(name.substring(prefix.length()), value);
                }
            }
        }
        if (flat.isEmpty()) {
            return Map.of();
        }
        Map<Object, Object> root = new LinkedHashMap<>();
        flat.forEach((path, value) -> nestPut(root, tokenize(path), 0, value));
        @SuppressWarnings("unchecked")
        Map<String, Object> normalized = (Map<String, Object>) normalize(root);
        return normalized;
    }

    private static final java.util.regex.Pattern PATH_TOKEN =
            java.util.regex.Pattern.compile("([^.\\[\\]]+)|\\[(\\d+)]");

    private static List<Object> tokenize(String path) {
        List<Object> tokens = new ArrayList<>();
        java.util.regex.Matcher matcher = PATH_TOKEN.matcher(path);
        while (matcher.find()) {
            if (matcher.group(1) != null) {
                tokens.add(matcher.group(1));
            } else {
                tokens.add(Integer.parseInt(matcher.group(2)));
            }
        }
        return tokens;
    }

    @SuppressWarnings("unchecked")
    private static void nestPut(Map<Object, Object> node, List<Object> tokens, int index, Object value) {
        Object token = tokens.get(index);
        if (index == tokens.size() - 1) {
            node.put(token, value);
            return;
        }
        Object child = node.get(token);
        if (!(child instanceof Map)) {
            child = new LinkedHashMap<Object, Object>();
            node.put(token, child);
        }
        nestPut((Map<Object, Object>) child, tokens, index + 1, value);
    }

    /** 整数键且连续的 Map 视为列表（属性名里 {@code [0]} 的索引）。 */
    @SuppressWarnings("unchecked")
    private static Object normalize(Object node) {
        if (!(node instanceof Map<?, ?> map)) {
            return node;
        }
        boolean listLike = !map.isEmpty() && map.keySet().stream().allMatch(key -> key instanceof Integer);
        if (listLike) {
            TreeMap<Integer, Object> sorted = new TreeMap<>();
            map.forEach((key, value) -> sorted.put((Integer) key, normalize(value)));
            return new ArrayList<>(sorted.values());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((key, value) -> out.put(String.valueOf(key), normalize(value)));
        return out;
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
