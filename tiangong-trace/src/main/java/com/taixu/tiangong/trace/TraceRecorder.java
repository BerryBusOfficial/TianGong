package com.taixu.tiangong.trace;

import com.taixu.tiangong.common.agent.call.AgentCallContext;
import com.taixu.tiangong.llm.message.Message;

import java.time.Instant;
import java.util.List;

/**
 * trace 记录入口：静态安装一个 {@link TraceStore}，框架在模型调用/工具执行处上报。<br>
 * <br>
 * 与 {@code ToolGovernance} 同一模式：执行器在 core，trace 是可选旁路；不装 store 时
 * 只构造记录、不落库（零副作用），单测/纯 llm 环境不受影响。
 */
public final class TraceRecorder {

    private static volatile TraceStore store;

    private TraceRecorder() {
    }

    public static void install(TraceStore traceStore) {
        store = traceStore;
    }

    public static void reset() {
        store = null;
    }

    public static boolean installed() {
        return store != null;
    }

    public static LlmTraceRecord recordLlm(String model,
                                           List<Message> messages,
                                           String result,
                                           long promptTokens,
                                           long completionTokens,
                                           long totalTokens,
                                           long costMicros,
                                           String currency,
                                           long durationMs,
                                           String status,
                                           String error) {
        AgentCallContext context = AgentCallContext.current();
        LlmTraceRecord record = new LlmTraceRecord(
                attr(context, "runId", context == null ? null : context.traceId()),
                attr(context, "turnId", null),
                attr(context, "node", null),
                model,
                PromptHasher.hash(messages),
                TextRedactor.redact(join(messages), 800),
                TextRedactor.redact(result, 800),
                promptTokens,
                completionTokens,
                totalTokens,
                costMicros,
                currency,
                durationMs,
                status,
                error,
                Instant.now());
        TraceStore current = store;
        if (current != null) {
            current.record(record);
        }
        return record;
    }

    public static ToolTraceRecord recordTool(String toolName,
                                             String callId,
                                             String argumentsJson,
                                             String result,
                                             String status,
                                             long durationMs,
                                             String error) {
        AgentCallContext context = AgentCallContext.current();
        ToolTraceRecord record = new ToolTraceRecord(
                attr(context, "runId", context == null ? null : context.traceId()),
                attr(context, "turnId", null),
                attr(context, "node", null),
                toolName,
                callId,
                TextRedactor.redact(argumentsJson, 400),
                TextRedactor.redact(result, 400),
                status,
                durationMs,
                error,
                Instant.now());
        TraceStore current = store;
        if (current != null) {
            current.record(record);
        }
        return record;
    }

    private static String join(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Message message : messages) {
            if (message == null) {
                continue;
            }
            text.append('[').append(message.getRole()).append("] ")
                    .append(message.getContent() == null ? "" : message.getContent())
                    .append('\n');
        }
        return text.toString();
    }

    private static String attr(AgentCallContext context, String key, String fallback) {
        if (context == null) {
            return fallback;
        }
        Object value = context.attributes().get(key);
        return value == null ? fallback : String.valueOf(value);
    }
}
