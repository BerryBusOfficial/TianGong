package com.taixu.tiangong.core.config;

import lombok.extern.slf4j.Slf4j;
import com.taixu.tiangong.common.config.ConfigTree;
import com.taixu.tiangong.common.exception.SystemException;
import com.taixu.tiangong.common.resource.ResourceLoader;
import com.taixu.tiangong.common.util.YamlUtil;
import com.taixu.tiangong.llm.builder.RequestBuilder;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

@Slf4j
public final class ConfigLoader {

    /**
     * V1设计，后期会支持不同环境的配置加载
     */
    private static final String[] SEARCH_PATHS = {"agent.yml", "agent.yaml"};

    public static void load() throws SystemException {
        for (String path : SEARCH_PATHS) {
            if (!ResourceLoader.exists(path)) {
                continue;
            }
            loadFile(path);
        }
    }

    // 保留无参（默认 SEARCH_PATHS），新增带参重载
    public static void load(String configPath, String profile) throws SystemException {
        if (configPath != null && !configPath.isBlank()) {
            loadFile(configPath);
            return;
        }
        if (profile != null && !profile.isBlank()) {
            loadFile("agent-" + profile + ".yaml");
            return;
        }
        for (String path : SEARCH_PATHS) {
            if (ResourceLoader.exists(path)) {
                loadFile(path);
                break;
            }
        }
    }

    private static void loadFile(String path) throws SystemException {
        try {
            log.info("加载配置文件：{}", path);
            ConfigTree tree = new ConfigTree(YamlUtil.loadYaml(path));
            ConfigHolder.init(tree);
            applyLlmTimeout(tree);
        } catch (IOException e) {
            throw new SystemException("加载配置失败: " + path, e);
        }
    }

    /**
     * 从内存配置树装载（宿主集成用：宿主把 Environment 与 agent.yaml 合并后的结果交给框架）。<br>
     * 语义与 {@link #load(String, String)} 一致：整体替换 + 应用 {@code llm.request-timeout}。
     *
     * @param source 配置树（顶层键与 agent.yaml 同构，如 {@code agent.*} / {@code context.*}）
     */
    public static void loadFrom(Map<String, Object> source) throws SystemException {
        ConfigTree tree = new ConfigTree(source);
        ConfigHolder.init(tree);
        applyLlmTimeout(tree);
    }

    /**
     * 把 {@code llm.request-timeout}（秒）注入 LLM 请求超时。<br>
     * <b>为什么必须在这里做</b>：依赖方向是 {@code core → llm}，llm 读不到 ConfigHolder，
     * 只能由 core 在配置就绪后反向注入。历史上这个超时被写死成 5 分钟且无任何入口，
     * 本地小模型（开思考 + 工具调用）单次请求常常顶到这个天花板，应用却完全调不了。
     */
    private static void applyLlmTimeout(ConfigTree tree) throws SystemException {
        Object raw = tree.getOrDefault("llm.request-timeout", null);
        if (raw == null) {
            return;
        }
        long seconds;
        try {
            seconds = Long.parseLong(raw.toString().trim());
        } catch (NumberFormatException e) {
            throw new SystemException("llm.request-timeout 必须是整数秒: " + raw, e);
        }
        if (seconds <= 0) {
            throw new SystemException("llm.request-timeout 必须为正数: " + raw);
        }
        RequestBuilder.setDefaultTimeout(Duration.ofSeconds(seconds));
        log.info("LLM 请求超时已设为 {} 秒（llm.request-timeout）", seconds);
    }

}
