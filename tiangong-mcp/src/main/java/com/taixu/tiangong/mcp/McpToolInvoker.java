package com.taixu.tiangong.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import com.taixu.tiangong.core.function.ToolInvoker;

import java.util.Map;

/**
 * MCP 工具调用器：把 MCP server 的 tool 包装为统一 {@link ToolInvoker}，调用 client.callTool。
 */
@RequiredArgsConstructor
public class McpToolInvoker implements ToolInvoker {

    private final McpSyncClient client;
    private final String toolName;

    @Override
    public Object invoke(Map<String, Object> arguments) {
        McpSchema.CallToolResult result = client.callTool(
                McpSchema.CallToolRequest.builder(toolName)
                        .arguments(arguments)
                        .build());
        if (Boolean.TRUE.equals(result.isError())) {
            throw new IllegalStateException("MCP 工具执行失败: " + toolName + " -> " + result.content());
        }
        if (result.structuredContent() != null) {
            return result.structuredContent();
        }
        if (result.content() != null) {
            return result.content().toString();
        }
        return null;
    }
}
