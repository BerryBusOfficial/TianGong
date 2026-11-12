package com.taixu.tiangong.mcp;

/**
 * MCP 传输类型。
 */
public enum McpTransportType {
    /**
     * 子进程（command + args）
     */
    STDIO,
    /**
     * Streamable HTTP
     */
    STREAM_HTTP,
    /**
     * SSE
     */
    SSE,
}
