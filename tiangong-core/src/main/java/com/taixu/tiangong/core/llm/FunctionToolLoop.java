package com.taixu.tiangong.core.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import com.taixu.tiangong.common.agent.call.AgentCallContext;
import com.taixu.tiangong.common.function.Arguments;
import com.taixu.tiangong.common.util.JsonUtil;
import com.taixu.tiangong.core.function.AgentFunctionDefinition;
import com.taixu.tiangong.core.function.FunctionRegistry;
import com.taixu.tiangong.core.function.ToolAccessDeniedException;
import com.taixu.tiangong.core.function.ToolGovernance;
import com.taixu.tiangong.core.function.ToolResultLimiter;
import com.taixu.tiangong.guardrail.GuardrailResult;
import com.taixu.tiangong.guardrail.GuardrailService;
import com.taixu.tiangong.guardrail.GuardrailStage;
import com.taixu.tiangong.llm.api.request.chat.ChatRequest;
import com.taixu.tiangong.llm.client.LlmClient;
import com.taixu.tiangong.llm.event.stream.ChatStream;
import com.taixu.tiangong.llm.event.stream.ToolCall;
import com.taixu.tiangong.llm.event.stream.ToolLoop;
import com.taixu.tiangong.llm.factory.MessageFactory;
import com.taixu.tiangong.llm.message.AssistantMessage;
import com.taixu.tiangong.llm.message.Message;
import com.taixu.tiangong.protocol.event.AgentEvent;
import com.taixu.tiangong.protocol.event.AgentEventScope;
import com.taixu.tiangong.trace.TraceRecorder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        AgentEventScope.emit(new AgentEvent.ToolCall(call.id(), call.name(), call.arguments()));
        long startNanos = System.nanoTime();

        AgentFunctionDefinition definition = functions.get(call.name()).orElse(null);
        if (definition == null) {
            String text = "工具未注册: " + call.name();
            log.warn("模型调用了未注册的工具 [{}]，已作为错误结果回放（检查 @AgentFunction 是否被扫描到）", call.name());
            emitToolResult(call, "failed", text, startNanos);
            return text;
        }
        try {
            Object result = ToolGovernance.invoke(
                    definition, Arguments.fromJson(call.arguments()).asMap(), call.name());
            String text = ToolResultLimiter.limit(toResultText(result));
            GuardrailResult guarded = GuardrailService.inspect(
                    GuardrailStage.TOOL_OUTPUT, text, AgentCallContext.current(),
                    Map.of("tool", call.name()));
            if (guarded.blocked()) {
                String blocked = "工具结果被护栏拦截: " + guarded.reason();
                log.warn("工具 [{}] 的输出被护栏拦截: {}", call.name(), guarded.reason());
                emitToolResult(call, "blocked", blocked, startNanos);
                return blocked;
            }
            text = guarded.text();
            log.info("工具调用 [{}] {} -> {}", call.name(), call.arguments(), abbreviate(text));
            emitToolResult(call, "success", text, startNanos);
            return text;
        } catch (ToolAccessDeniedException e) {
            String text = "工具调用被拒绝: " + e.getMessage();
            log.warn("工具 [{}] 被权限/审批策略拒绝: {}", call.name(), e.getMessage());
            emitToolResult(call, "denied", text, startNanos);
            return text;
        } catch (Exception e) {
            String text = "工具执行失败: " + e.getMessage();
            log.warn("工具 [{}] 执行失败: {}", call.name(), e.getMessage(), e);
            emitToolResult(call, "failed", text, startNanos);
            return text;
        }
    }

    private void emitToolResult(ToolCall call, String status, String result, long startNanos) {
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        AgentEventScope.emit(new AgentEvent.ToolResult(
                call.id(), call.name(), status, result, durationMs));
        TraceRecorder.recordTool(call.name(), call.id(), call.arguments(), result, status,
                durationMs, "success".equals(status) ? null : result);
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
