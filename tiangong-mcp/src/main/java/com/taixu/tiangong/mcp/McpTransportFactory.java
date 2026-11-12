package com.taixu.tiangong.mcp;

import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;

import java.net.http.HttpRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * MCP transport 工厂：按配置创建 stdio / stream-http / sse 客户端传输。
 */
public final class McpTransportFactory {

    private McpTransportFactory() {
    }

    public static McpClientTransport create(McpServerConfig config) {
        McpTransportType type = McpTransportType.valueOf(
                config.getTransport().toUpperCase().replace('-', '_'));
        return switch (type) {
            case STDIO -> createStdio(config);
            case STREAM_HTTP -> createStreamHttp(config);
            case SSE -> createSse(config);
        };
    }

    private static McpClientTransport createStdio(McpServerConfig config) {
        ServerParameters.Builder parameters = new ServerParameters.Builder(config.getCommand());
        if (config.getArgs() != null) {
            parameters.args(config.getArgs());
        }
        if (config.getEnv() != null) {
            Map<String, String> merged = new HashMap<>(System.getenv());
            merged.putAll(config.getEnv());
            parameters.env(merged);
        }
        return new StdioClientTransport(parameters.build(), McpJsonDefaults.getMapper());
    }

    private static McpClientTransport createStreamHttp(McpServerConfig config) {
        HttpClientStreamableHttpTransport.Builder builder =
                HttpClientStreamableHttpTransport.builder(config.getUrl());
        applyHeaders(builder, config.getHeaders());
        return builder.build();
    }

    private static McpClientTransport createSse(McpServerConfig config) {
        HttpClientSseClientTransport.Builder builder =
                HttpClientSseClientTransport.builder(config.getUrl());
        applyHeaders(builder, config.getHeaders());
        return builder.build();
    }

    private static void applyHeaders(HttpClientStreamableHttpTransport.Builder builder, Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return;
        }
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();
        headers.forEach(requestBuilder::header);
        builder.requestBuilder(requestBuilder);
    }

    private static void applyHeaders(HttpClientSseClientTransport.Builder builder, Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return;
        }
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();
        headers.forEach(requestBuilder::header);
        builder.requestBuilder(requestBuilder);
    }
}
