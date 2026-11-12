package org.wutong.tiangong.core.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.wutong.tiangong.common.function.Arguments;
import org.wutong.tiangong.common.util.JsonUtil;
import org.wutong.tiangong.core.function.AgentFunctionDefinition;
import org.wutong.tiangong.core.function.FunctionRegistry;
import org.wutong.tiangong.llm.api.request.chat.ChatRequest;
import org.wutong.tiangong.llm.client.LlmClient;
import org.wutong.tiangong.llm.event.stream.ChatStream;
import org.wutong.tiangong.llm.event.stream.ToolCall;
import org.wutong.tiangong.llm.event.stream.ToolLoop;
import org.wutong.tiangong.llm.factory.MessageFactory;
import org.wutong.tiangong.llm.message.AssistantMessage;
import org.wutong.tiangong.llm.message.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具循环执行器：把模型请求的工具调用映射到 {@link FunctionRegistry} 里的注册函数，<br>
 * 执行结果以 ToolMessage 回放给模型，直到模型不再请求工具。<br>
 * <br>
 * <h2>回放的消息序列（OpenAI 协议契约）</h2>
 * <pre>
 *   …原始消息…
 *   assistant(tool_calls=[{id:"call_1", function:{name:"get_sys_time", …}}])   ← 必须有，且 id 对应
 *   tool(content="…执行结果…", tool_call_id="call_1")                          ← 结果按 id 对回调用
 *   （续发请求 → 模型基于工具结果继续说话）
 * </pre>
 * <br>
 * <h2>失败策略：工具报错回放给模型，不炸整条流</h2>
 * 未注册的工具、参数解析失败、工具执行抛异常 —— 都转成结果文本回给模型
 * （"工具未注册: xxx" / "工具执行失败: …"），让它看到失败原因自行决定下一步。
 * 直接抛出去的话，模型连解释的机会都没有，用户看到的是一条断掉的对话。<br>
 * 「未注册」同时会打 warn 日志 —— 那是注册配置问题，该被开发者看见，而不是被模型悄悄吸收。
 */
@Slf4j
public class FunctionToolLoop implements ToolLoop {

    private final LlmClient<?, ?> client;
    private final ChatRequest request;
    private final FunctionRegistry functions;
    /** 滚动消息列表：初始为原始请求消息，每轮追加「助手调用 + 工具结果」 */
    private final List<Message> messages;

    public FunctionToolLoop(LlmClient<?, ?> client, ChatRequest request, FunctionRegistry functions) {
        this.client = client;
        this.request = request;
        this.functions = functions;
        this.messages = new ArrayList<>(request.getMessages());
    }

    @Override
    public ChatStream<?, ?> next(String assistantContent, List<ToolCall> calls) {
        messages.add(assistantToolCalls(assistantContent, calls));
        for (ToolCall call : calls) {
            messages.add(MessageFactory.createToolMsg(execute(call), call.id()));
        }
        // 原请求只换 messages：model / tools / chatOptions 原样保留
        return client.stream(request.toBuilder().messages(List.copyOf(messages)).build());
    }

    /**
     * 执行一个工具调用；一切失败都进结果文本（见类注释的失败策略）。
     */
    private String execute(ToolCall call) {
        AgentFunctionDefinition definition = functions.get(call.name()).orElse(null);
        if (definition == null) {
            log.warn("模型调用了未注册的工具 [{}]，已作为错误结果回放（检查 @AgentFunction 是否被扫描到）", call.name());
            return "工具未注册: " + call.name();
        }
        try {
            Object result = definition.invoke(Arguments.fromJson(call.arguments()).asMap());
            String text = toResultText(result);
            log.info("工具调用 [{}] {} -> {}", call.name(), call.arguments(), abbreviate(text));
            return text;
        } catch (Exception e) {
            log.warn("工具 [{}] 执行失败: {}", call.name(), e.getMessage(), e);
            return "工具执行失败: " + e.getMessage();
        }
    }

    /**
     * 结果序列化为 JSON 文本。<br>
     * 字符串会带引号 —— 协议侧工具结果本就是 JSON 值，带引号语义更明确；
     * 序列化失败退回 {@code String.valueOf}，不为了遥测功能弄丢执行结果。
     */
    private String toResultText(Object result) {
        if (result == null) {
            return "null";
        }
        try {
            return JsonUtil.toJson(result);
        } catch (JsonProcessingException e) {
            return String.valueOf(result);
        }
    }

    private AssistantMessage assistantToolCalls(String content, List<ToolCall> calls) {
        List<AssistantMessage.ToolCall> toolCalls = calls.stream()
                .map(call -> new AssistantMessage.ToolCall(call.id(), "function",
                        new AssistantMessage.ToolCall.Function(call.name(), call.arguments())))
                .toList();
        // content 用空串而不是 null：协议要求这条消息带上 content 字段
        return new AssistantMessage(content == null ? "" : content, null, toolCalls);
    }

    private String abbreviate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
