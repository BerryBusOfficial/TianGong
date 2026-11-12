package org.wutong.tiangong.spring;

import lombok.Data;
import lombok.Getter;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 集成配置（{@code tiangong.*}）。<br>
 * <br>
 * 键与默认值：
 * <ul>
 *     <li>{@code tiangong.enabled}（true）：false 时整个桥不启动（不注册代理、不自举）</li>
 *     <li>{@code tiangong.config}：agent.yaml 路径（支持 {@code classpath:} 前缀）；
 *         不配则隐式探测 {@code classpath:agent.yaml}</li>
 *     <li>{@code tiangong.packages}：额外扫描包（逗号分隔）</li>
 *     <li>{@code tiangong.config-namespaces}（{@code agent,context,llm,pet,tiangong}）：
 *         从 Spring Environment 抽取并<b>覆盖</b>文件层的命名空间</li>
 *     <li>{@code tiangong.service.timeout-seconds}（60）：服务调用等待出口的超时</li>
 * </ul>
 * 手工从 Environment 读取（不引入 Binder/ConfigurationProperties 绑定链，保持依赖最小）。
 */
@Data
public class TianGongProperties {

    private boolean enabled = true;
    private String config;
    private List<String> packages = new ArrayList<>();
    private List<String> configNamespaces = new ArrayList<>(List.of("agent", "context", "llm", "pet", "tiangong"));
    private long serviceTimeoutSeconds = 60;

    public static TianGongProperties from(Environment environment) {
        TianGongProperties props = new TianGongProperties();
        props.enabled = environment.getProperty("tiangong.enabled", Boolean.class, Boolean.TRUE);
        props.config = environment.getProperty("tiangong.config");

        String packages = environment.getProperty("tiangong.packages");
        if (packages != null && !packages.isBlank()) {
            props.packages = split(packages);
        }
        String namespaces = environment.getProperty("tiangong.config-namespaces");
        if (namespaces != null && !namespaces.isBlank()) {
            props.configNamespaces = split(namespaces);
        }
        Long timeout = environment.getProperty("tiangong.service.timeout-seconds", Long.class);
        if (timeout != null && timeout > 0) {
            props.serviceTimeoutSeconds = timeout;
        }
        return props;
    }

    private static List<String> split(String value) {
        List<String> parts = new ArrayList<>();
        for (String piece : value.split("[,;]")) {
            if (!piece.isBlank()) {
                parts.add(piece.trim());
            }
        }
        return parts;
    }
}
