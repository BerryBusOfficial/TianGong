package org.wutong.tiangong.mcp;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * MCP server 配置（agent.mcp 列表项）。<br>
 * stdio：command + args + env；stream-http/sse：url + headers。
 */
@Data
public class McpServerConfig {

    /**
     * 注册名（工具名前缀 = serverName.toolName）
     */
    private String name;

    /**
     * 传输类型：stdio / stream-http / sse
     */
    private String transport;

    /**
     * stdio：启动命令
     */
    private String command;

    /**
     * stdio：启动参数
     */
    private List<String> args;

    /**
     * stdio：环境变量
     */
    private Map<String, String> env;

    /**
     * stream-http/sse：端点地址
     */
    private String url;

    /**
     * stream-http/sse：请求头
     */
    private Map<String, String> headers;
}
