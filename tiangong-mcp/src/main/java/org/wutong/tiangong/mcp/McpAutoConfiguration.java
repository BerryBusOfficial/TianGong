package org.wutong.tiangong.mcp;

import org.wutong.tiangong.annotations.annotation.framework.AutoConfiguration;
import org.wutong.tiangong.annotations.annotation.framework.enable.EnableMcp;
import org.wutong.tiangong.core.container.context.framework.FrameworkConfiguration;
import org.wutong.tiangong.core.container.context.framework.FrameworkConfigurator;

/**
 * MCP 自动配置：用户启用自动装配（@TianGongApplicationStarter / @EnableAutoConfiguration）后，
 * 自动加载 agent.mcp 配置并注册 MCP 工具进 FunctionRegistry；注册关闭钩子释放连接。<br>
 * 受 @EnableMcp 开关控制：未标注或 value=false 时不加载。
 */
@AutoConfiguration
public class McpAutoConfiguration implements FrameworkConfiguration {

    @Override
    public void configure(FrameworkConfigurator configurator) {
        if (!isMcpEnabled(configurator)) {
            return;
        }
        McpRegistry registry = new McpRegistry(configurator.functionRegistry());
        registry.load();
        Runtime.getRuntime().addShutdownHook(new Thread(registry::close, "tiangong-mcp-shutdown"));
    }

    /**
     * 读取 @EnableMcp 开关：从扫描器元数据中查找 @EnableMcp，任一标注且 value=true 即开启。
     * 与 @EnableScheduling / @EnableApi 一致，都走扫描器元数据
     * —— 这样才能覆盖启动类所在的业务包（另起 ClassGraph 硬编码框架包会漏）。
     * 未标注 @EnableMcp 时默认关闭。
     */
    private static boolean isMcpEnabled(FrameworkConfigurator configurator) {
        return configurator.scanner().getEnableMetadata().stream()
                .anyMatch(meta -> meta.enableAnnotation() == EnableMcp.class && meta.enabled());
    }
}
