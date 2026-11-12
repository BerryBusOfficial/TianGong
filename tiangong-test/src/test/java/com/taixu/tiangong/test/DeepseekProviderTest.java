package com.taixu.tiangong.test;

import org.junit.jupiter.api.Test;
import com.taixu.tiangong.llm.api.request.chat.ChatRequest;
import com.taixu.tiangong.llm.api.request.chat.DeepseekChatRequest;
import com.taixu.tiangong.llm.api.response.chat.DeepseekChatResponse;
import com.taixu.tiangong.llm.message.HumanMessage;
import com.taixu.tiangong.llm.message.Message;
import com.taixu.tiangong.llm.provider.DeepseekProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DeepSeek provider 的离线验证（不联网，全部喂固定数据）。<br>
 * <b>tool_choice 下发纪律</b>：OpenAI 系协议校验 tool_choice 必须伴随 tools，
 * 无工具请求（如审查链 json 模式的结构化输出调用）带上它会被网关 400 拒绝
 * —— 已在真实运行里复现过，这里固化成回归用例。
 */
class DeepseekProviderTest {

    private final DeepseekProvider provider = new DeepseekProvider();

    @Test
    void toolChoiceOmittedWhenNoTools() {
        ChatRequest request = ChatRequest.builder()
                .model("deepseek-v4-flash")
                .messages(List.<Message>of(new HumanMessage("hi")))
                .build();

        DeepseekChatRequest body = provider.toChatRequest(request);

        assertNull(body.getToolChoice(), "无 tools 不应下发 tool_choice");
        assertFalse(body.toMap().containsKey("tool_choice"));
    }

    @Test
    void toolChoiceDefaultsToAutoWhenToolsPresent() {
        ChatRequest request = ChatRequest.builder()
                .model("deepseek-v4-flash")
                .messages(List.<Message>of(new HumanMessage("hi")))
                .tools(List.of(ChatRequest.Tool.builder()
                        .function(ChatRequest.Tool.Function.builder()
                                .name("report_finding")
                                .parameters(ChatRequest.JsonSchema.builder().build())
                                .build())
                        .build()))
                .build();

        DeepseekChatRequest body = provider.toChatRequest(request);

        assertEquals(DeepseekChatRequest.ToolChoice.Type.AUTO, body.getToolChoice().value());
        assertTrue(body.toMap().containsKey("tool_choice"));
    }

    /** 网关会回 request_id（排障追踪号）：必须能解析，否则每次调用都刷「未知字段被忽略」的 WARN */
    @Test
    void parsesRequestIdFromGatewayResponse() {
        String json = """
                {
                  "id": "chatcmpl-abc",
                  "object": "chat.completion",
                  "created": 1700000000,
                  "model": "deepseek-v4-flash",
                  "request_id": "req_123456",
                  "choices": [
                    {"index": 0, "message": {"role": "assistant", "content": "你好"},
                     "finish_reason": "stop"}
                  ],
                  "usage": {"prompt_tokens": 9, "completion_tokens": 5, "total_tokens": 14}
                }
                """;

        DeepseekChatResponse response = provider.parseChatResponse(json);

        assertEquals("req_123456", response.requestId());
        assertEquals("你好", response.getContent());
    }
}
