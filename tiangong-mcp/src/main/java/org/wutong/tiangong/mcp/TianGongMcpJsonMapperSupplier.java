package org.wutong.tiangong.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;

/**
 * 提供基于 Jackson 2 的 {@link McpJsonMapper}（ServiceLoader 注册）。
 */
public class TianGongMcpJsonMapperSupplier implements McpJsonMapperSupplier {

    @Override
    public McpJsonMapper get() {
        return new TianGongMcpJsonMapper();
    }
}
