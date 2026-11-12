package com.taixu.tiangong.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.taixu.tiangong.common.resource.ResourceLoader;
import com.taixu.tiangong.core.config.ConfigHolder;
import com.taixu.tiangong.core.config.ConfigLoader;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class McpRegistryTest {

    @BeforeAll
    static void loadConfig() throws Exception {
        ResourceLoader.load(McpRegistryTest.class);
        ConfigLoader.load("agent-mcp.yml", null);
    }

    @Test
    void configParsed() {
        List<McpServerConfig> servers = ConfigHolder.getValue("agent.mcp", new TypeReference<>() {
        });
        assertNotNull(servers);
        assertEquals(3, servers.size());

        McpServerConfig stdio = servers.get(0);
        assertEquals("echo-server", stdio.getName());
        assertEquals("stdio", stdio.getTransport());
        assertEquals("echo", stdio.getCommand());
        assertEquals(List.of("hello"), stdio.getArgs());

        McpServerConfig http = servers.get(1);
        assertEquals("stream-http", http.getTransport());
        assertEquals("http://localhost:3001/mcp", http.getUrl());
        assertEquals("Bearer test", http.getHeaders().get("Authorization"));
    }

    @Test
    void stdioTransportCreated() {
        McpServerConfig config = new McpServerConfig();
        config.setName("s");
        config.setTransport("stdio");
        config.setCommand("echo");
        McpClientTransport transport = McpTransportFactory.create(config);
        assertInstanceOf(StdioClientTransport.class, transport);
    }

    @Test
    void streamHttpTransportCreated() {
        McpServerConfig config = new McpServerConfig();
        config.setName("s");
        config.setTransport("stream-http");
        config.setUrl("http://localhost:3001/mcp");
        McpClientTransport transport = McpTransportFactory.create(config);
        assertInstanceOf(HttpClientStreamableHttpTransport.class, transport);
    }

    @Test
    void sseTransportCreated() {
        McpServerConfig config = new McpServerConfig();
        config.setName("s");
        config.setTransport("sse");
        config.setUrl("http://localhost:3002/sse");
        McpClientTransport transport = McpTransportFactory.create(config);
        assertInstanceOf(HttpClientSseClientTransport.class, transport);
    }
}
