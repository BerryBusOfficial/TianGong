package com.taixu.tiangong.core.llm;

import com.taixu.tiangong.common.util.JsonUtil;
import com.taixu.tiangong.llm.api.request.chat.ChatRequest;
import com.taixu.tiangong.llm.api.response.chat.ChatResponse;
import com.taixu.tiangong.llm.client.LlmClient;
import com.taixu.tiangong.llm.factory.MessageFactory;
import com.taixu.tiangong.llm.message.AssistantMessage;
import com.taixu.tiangong.llm.message.Message;
import com.taixu.tiangong.protocol.event.AgentEvent;
import com.taixu.tiangong.protocol.event.AgentEventScope;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 结构化输出执行器：请求 → 解析 → 校验 → 不合格则带错误修复重试。<br>
 * <br>
 * <h2>为什么要有修复轮</h2>
 * 即使 provider 支持 {@code json_schema}，模型仍可能漏字段、把数字写成字符串、
 * 或在 JSON 外套一段解释。一次失败就让整条业务链报错，是把模型的概率性当成确定性；
 * 带「具体哪个路径错了」重试一次，绝大多数问题能收敛。<br>
 * <br>
 * <b>边界</b>：修复轮只修格式，不修业务事实。金额算错、字段编造不属于 schema 能发现的
 * 问题，不能被这里「校验通过」掩盖。
 */
public final class StructuredOutputRunner {

    public static final int DEFAULT_MAX_REPAIRS = 2;

    private StructuredOutputRunner() {
    }

    public static <T> T run(LlmClient<?, ?> client, ChatRequest request, StructuredOutput spec) {
        return run(client, request, spec, DEFAULT_MAX_REPAIRS);
    }

    /**
     * @param maxRepairs 首次失败后最多再请求几次（0 = 不修复）
     */
    public static <T> T run(LlmClient<?, ?> client, ChatRequest request,
                            StructuredOutput spec, int maxRepairs) {
        if (client == null || request == null || spec == null) {
            throw new IllegalArgumentException("client / request / spec 均不能为空");
        }
        List<Message> messages = new ArrayList<>(request.getMessages());
        // schema 放在最前：Deepseek 的 json_object 只保证「是 JSON」，不保证字段；
        // 把 schema 明确写进系统提示词，修复轮才有稳定的判据。
        messages.add(0, MessageFactory.createSystemMsg(schemaPrompt(spec)));

        ChatRequest current = request.toBuilder()
                .messages(List.copyOf(messages))
                .responseFormat(spec.toResponseFormat())
                .build();

        StructuredOutputException last = null;
        for (int attempt = 0; attempt <= Math.max(0, maxRepairs); attempt++) {
            ChatResponse<?> response = client.chat(current).join();
            String content = response == null ? null : response.getContent();
            try {
                T value = StructuredOutputParser.parse(content, spec);
                emitStructured(spec, value);
                return value;
            } catch (StructuredOutputException e) {
                last = e;
                if (attempt >= maxRepairs) {
                    break;
                }
                messages.add(new AssistantMessage(content == null ? "" : content));
                messages.add(MessageFactory.createHumanMsg(repairPrompt(e)));
                current = current.toBuilder().messages(List.copyOf(messages)).build();
            }
        }
        throw last != null ? last : new StructuredOutputException("结构化输出失败", List.of(), null, null);
    }

    private static String schemaPrompt(StructuredOutput spec) {
        return """
                你必须只输出一个符合下面 JSON Schema 的 JSON 值。
                规则：
                1. 只输出 JSON 本身，不要 Markdown 代码块，不要解释文字；
                2. 所有 required 字段必须存在，字段名严格按 schema；
                3. 不要编造 schema 之外的字段。
                schema 名：%s
                JSON Schema：
                %s
                """.formatted(spec.name(), spec.schemaJson());
    }

    private static String repairPrompt(StructuredOutputException e) {
        String detail = e.errors().isEmpty() ? e.getMessage() : String.join("；", e.errors());
        return "上一次输出不符合约定：" + detail
                + "。请只输出修正后的 JSON，不要任何解释或代码块。";
    }

    @SuppressWarnings("unchecked")
    private static void emitStructured(StructuredOutput spec, Object value) {
        if (AgentEventScope.capture() == null) {
            return;
        }
        Map<String, Object> data;
        if (value instanceof Map<?, ?> map) {
            data = (Map<String, Object>) map;
        } else {
            data = Map.of("value", value);
        }
        AgentEventScope.emit(new AgentEvent.Structured(spec.name(), data));
    }

    /**
     * 把已校验的对象转成事件里的扁平 Map（供直接发事件的场景复用）。
     */
    public static Map<String, Object> toEventData(Object value) {
        return JsonUtil.bodyMapper().convertValue(value,
                JsonUtil.bodyMapper().getTypeFactory()
                        .constructMapType(Map.class, String.class, Object.class));
    }
}
