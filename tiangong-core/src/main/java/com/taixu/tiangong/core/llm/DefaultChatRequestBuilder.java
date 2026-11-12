package com.taixu.tiangong.core.llm;

import com.taixu.tiangong.common.agent.call.AgentCallContext;
import com.taixu.tiangong.core.function.AgentFunctionDefinition;
import com.taixu.tiangong.core.function.FunctionRegistry;
import com.taixu.tiangong.core.function.ToolAccessPolicy;
import com.taixu.tiangong.core.function.ToolSchemaConverter;
import com.taixu.tiangong.llm.api.request.chat.ChatRequest;
import com.taixu.tiangong.llm.message.Message;

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

    @Override
    public ChatRequest build(List<Message> messages, StructuredOutput output) {
        return build(messages, null, ToolParam.NONE, output);
    }

    @Override
    public ChatRequest build(List<Message> messages, Boolean stream, ToolParam toolParam,
                             StructuredOutput output) {
        ChatRequest base = build(messages, stream, toolParam);
        return base.toBuilder()
                .responseFormat(output == null ? null : output.toResponseFormat())
                .build();
    }

    private List<ChatRequest.Tool> resolveAll() {
        // 只把「调用者当前能用」的工具下发给模型：模型看不到无权工具，就不会请求它，
        // 也避免了「先诱导模型调用、再在服务端拒绝」这种既费 token 又难解释的流程。
        AgentCallContext context = AgentCallContext.current();
        return functionRegistry.values().stream()
                .filter(definition -> ToolAccessPolicy.isVisible(definition, context))
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
            ToolAccessPolicy.Decision access = ToolAccessPolicy.check(def);
            if (!access.allowed()) {
                throw new IllegalArgumentException(access.reason());
            }
            tools.add(ToolSchemaConverter.toTool(def));
        }
        return tools;
    }
}