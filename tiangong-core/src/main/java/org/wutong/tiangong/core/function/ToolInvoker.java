package org.wutong.tiangong.core.function;

import java.util.Map;

/**
 * 工具调用执行器：统一本地方法（@AgentFunction）与外部工具（MCP）的调用抽象。<br>
 * 本地函数 → {@link MethodInvoker}；MCP 工具 → McpToolInvoker。
 */
@FunctionalInterface
public interface ToolInvoker {

    /**
     * 执行工具调用
     *
     * @param arguments 参数（key = 参数名）
     * @return 执行结果
     */
    Object invoke(Map<String, Object> arguments);
}
