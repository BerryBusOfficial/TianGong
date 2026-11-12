package org.wutong.tiangong.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.wutong.tiangong.core.config.ConfigHolder;
import org.wutong.tiangong.core.function.AgentFunctionDefinition;
import org.wutong.tiangong.core.function.FunctionRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP 注册表：读取 agent.mcp 配置 → 建立连接 → 发现工具 → 注册进 {@link FunctionRegistry}。<br>
 * 工具命名：serverName.toolName（FunctionRegistry 全名），短名查询（toolName）也支持。
 */
@Slf4j
public class McpRegistry {

    private final FunctionRegistry functionRegistry;
    private final List<McpSyncClient> clients = new ArrayList<>();

    public McpRegistry(FunctionRegistry functionRegistry) {
        this.functionRegistry = functionRegistry;
    }

    /**
     * 从 agent.mcp 配置加载所有 MCP server 并注册其工具
     */
    public void load() {
        List<McpServerConfig> servers;
        try {
            servers = ConfigHolder.getValue("agent.mcp", new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("未配置 agent.mcp，跳过 MCP 加载");
            return;
        }
        if (servers == null || servers.isEmpty()) {
            log.warn("未配置 agent.mcp，跳过 MCP 加载");
            return;
        }
        for (McpServerConfig config : servers) {
            try {
                loadServer(config);
            } catch (Throwable e) {
                // 一个 server 挂了 = 少一批工具，而不是应用不可用 —— 不该拖垮启动。
                // 常见的踩法：token 还是占位符、npx 首次下载包超时、
                // Windows 上把 command 写成了 npx（得写 npx.cmd）。
                //
                // ⚠ 为什么连 Error 都接：SDK 在缺 JsonSchemaValidator 的 SPI 实现时抛的是
                // ServiceConfigurationError（**Error 不是 Exception**），只 catch Exception
                // 的话它会一路冒到 main 把整个应用带走 —— 实测正是这么崩的。
                // 外部可选服务的加载失败必须一律降级，不该由它决定应用生死。
                log.error("MCP server [{}] 加载失败，它的工具不可用（应用照常启动）: {}",
                        config.getName(), e.getMessage(), e);
            }
        }
    }

    private void loadServer(McpServerConfig config) {
        McpSyncClient client = McpClient.sync(McpTransportFactory.create(config))
                .build();
        client.initialize();
        clients.add(client);

        McpSchema.ListToolsResult toolsResult = client.listTools();
        if (toolsResult.tools() == null || toolsResult.tools().isEmpty()) {
            log.info("MCP server {} 无可用工具", config.getName());
            return;
        }
        List<String> names = new ArrayList<>();
        for (McpSchema.Tool tool : toolsResult.tools()) {
            registerTool(config.getName(), tool, client);
            names.add("%s.%s".formatted(config.getName(), tool.name()));
        }
        // 把工具名也打出来：排查「模型怎么没调这个工具」时，
        // 光知道「注册了 5 个」没用，得知道到底是哪 5 个。
        log.info("MCP server {} 注册 {} 个工具（transport={}）: {}",
                config.getName(), names.size(), config.getTransport(), names);
    }

    private void registerTool(String serverName, McpSchema.Tool tool, McpSyncClient client) {
        List<AgentFunctionDefinition.ParamDefinition> params = resolveParams(tool.inputSchema());
        AgentFunctionDefinition definition = new AgentFunctionDefinition(
                tool.name(),
                serverName,
                tool.description() != null ? tool.description() : "",
                null,
                null,
                params,
                // 原始 schema 也带上：扁平的 params 表达不了嵌套对象 / oneOf / enum 可选值，
                // 下发时优先用这份完整的（见 AgentFunctionDefinition#rawSchema）
                tool.inputSchema(),
                new McpToolInvoker(client, tool.name())
        );
        functionRegistry.register(definition);
    }

    /**
     * MCP inputSchema（JSON Schema）→ ParamDefinition 列表
     */
    @SuppressWarnings("unchecked")
    private List<AgentFunctionDefinition.ParamDefinition> resolveParams(Map<String, Object> inputSchema) {
        List<AgentFunctionDefinition.ParamDefinition> params = new ArrayList<>();
        if (inputSchema == null) {
            return params;
        }
        Map<String, Object> properties = (Map<String, Object>) inputSchema.get("properties");
        if (properties == null) {
            return params;
        }
        List<String> required = (List<String>) inputSchema.get("required");
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            Map<String, Object> prop = (Map<String, Object>) entry.getValue();
            params.add(new AgentFunctionDefinition.ParamDefinition(
                    entry.getKey(),
                    (String) prop.getOrDefault("type", "string"),
                    (String) prop.getOrDefault("description", ""),
                    required != null && required.contains(entry.getKey()),
                    ""                              // MCP 工具无 defaultValue
            ));
        }
        return params;
    }

    /**
     * 关闭所有已连接的 MCP client（应用关闭时调用）
     */
    public void close() {
        for (McpSyncClient client : clients) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("MCP client 关闭失败", e);
            }
        }
        clients.clear();
    }
}
