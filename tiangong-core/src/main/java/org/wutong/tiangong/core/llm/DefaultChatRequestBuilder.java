package org.wutong.tiangong.core.llm;

import org.wutong.tiangong.core.function.AgentFunctionDefinition;
import org.wutong.tiangong.core.function.FunctionRegistry;
import org.wutong.tiangong.core.function.ToolSchemaConverter;
import org.wutong.tiangong.llm.api.request.chat.ChatRequest;
import org.wutong.tiangong.llm.message.Message;

import java.util.ArrayList;
import java.util.List;

public final class DefaultChatRequestBuilder implements ChatRequestBuilder {

    private final LlmRegistry registry;
    private final String clientName;
    private final FunctionRegistry functionRegistry;

    public DefaultChatRequestBuilder(LlmRegistry registry, String clientName, FunctionRegistry functionRegistry) {
        this.registry = registry;
        this.clientName = clientName;
        this.functionRegistry = functionRegistry;
    }

    @Override
    public ChatRequest build(List<Message> messages) {
        return registry.buildChatRequest(clientName, messages);
    }

    @Override
    public ChatRequest build(List<Message> messages, ToolParam toolParam) {
        return build(messages, null, toolParam);
    }

    @Override
    public ChatRequest build(List<Message> messages, Boolean stream, ToolParam toolParam) {
        return registry.buildChatRequest(clientName, messages, stream,
                toolParam == ToolParam.NONE ? null : resolveAll());
    }

    @Override
    public ChatRequest build(List<Message> messages, Boolean stream) {
        return registry.buildChatRequest(clientName, messages, stream);
    }

    @Override
    public ChatRequest build(List<Message> messages, String... toolFunctions) {
        return build(messages, null, toolFunctions);
    }

    @Override
    public ChatRequest build(List<Message> messages, Boolean stream, String... toolFunctions) {
        return registry.buildChatRequest(clientName, messages, stream, resolve(toolFunctions));
    }

    private List<ChatRequest.Tool> resolveAll() {
        return functionRegistry.values().stream()
                .map(ToolSchemaConverter::toTool)
                .toList();
    }

    /**
     * 手动名：逐个查注册表（短名/全名双路都支持），查不到抛异常
     */
    private List<ChatRequest.Tool> resolve(String[] names) {
        List<ChatRequest.Tool> tools = new ArrayList<>();
        for (String name : names) {
            AgentFunctionDefinition def = functionRegistry.get(name)
                    .orElseThrow(() -> new IllegalArgumentException("未注册的 Agent 函数: " + name));
            tools.add(ToolSchemaConverter.toTool(def));
        }
        return tools;
    }
}