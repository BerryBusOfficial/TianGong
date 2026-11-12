package com.taixu.tiangong.llm.client;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import com.taixu.tiangong.common.type.Model;
import com.taixu.tiangong.common.type.impl.Openai;
import com.taixu.tiangong.llm.api.Api;
import com.taixu.tiangong.llm.api.OpenaiApi;
import com.taixu.tiangong.llm.api.request.Request;
import com.taixu.tiangong.llm.api.request.chat.ChatRequest;
import com.taixu.tiangong.llm.api.request.chat.OpenaiChatRequest;
import com.taixu.tiangong.llm.api.response.chat.chunk.OpenaiChatChunkResponse;
import com.taixu.tiangong.llm.api.response.model.OpenaiModelsResponse;
import com.taixu.tiangong.llm.event.result.OpenaiChatResult;
import com.taixu.tiangong.llm.event.stream.ChatStream;
import com.taixu.tiangong.llm.event.stream.OpenaiChatStream;
import com.taixu.tiangong.llm.event.stream.ToolCall;
import com.taixu.tiangong.llm.event.stream.ToolLoopSupport;
import com.taixu.tiangong.llm.provider.OpenaiProvider;

import java.io.InputStream;
import java.util.Map;

/**
 * OpenAI 本家客户端。<br>
 * 端点由 {@link OpenaiApi} 给出：{@code /v1/chat/completions}、{@code /v1/models}、{@code /v1/embeddings}。
 * 换兼容端点（vLLM / LM Studio / 国产推理服务）只要改 baseUrl —— 协议是同一套。
 */
@Data
@Slf4j
@com.taixu.tiangong.annotations.annotation.framework.component.LlmClient(provider = Openai.class)
public class OpenaiLlmClient implements LlmClient<Openai, OpenaiProvider> {

    /**
     * 本家默认地址。注意：只有 api.openai.com 是 OpenAI 自己，
     * 其它号称「OpenAI 兼容」的服务都是第三方实现。
     */
    public static final String DEFAULT_BASE_URL = "https://api.openai.com";

    private final String baseUrl;
    private final String apikey;

    private final OpenaiProvider provider;
    private final OpenaiApi openaiApi;

    public OpenaiLlmClient() {
        this(System.getenv("OPENAI_API_KEY"));
    }

    public OpenaiLlmClient(String apiKey) {
        this(DEFAULT_BASE_URL, apiKey);
    }

    public OpenaiLlmClient(String baseUrl, String apiKey) {
        this.apikey = apiKey;
        this.baseUrl = baseUrl;
        this.provider = new OpenaiProvider();
        this.openaiApi = new OpenaiApi(baseUrl, apiKey);
    }

    @Override
    public OpenaiProvider getProvider() {
        return provider;
    }

    @Override
    public Api getApi() {
        return openaiApi;
    }

    @Override
    public OpenaiChatResult chat(ChatRequest chatRequest) {
        try {
            OpenaiChatRequest body = getProvider().toChatRequest(chatRequest);
            Map<String, Object> map = body.toMap();
            if (body.getStream() == null || body.getStream().equals(Boolean.TRUE)) {
                map.put("stream", false);
            }
            String responseBody = getApi().chatCompletion(map);
            return new OpenaiChatResult(getProvider().parseChatResponse(responseBody));
        } catch (Exception e) {
            return new OpenaiChatResult(null, e);   // 错误进 ChatResult：onError 回调 / join 抛出
        }
    }

    /**
     * 流式对话。<br>
     * 返回类型是 {@link ChatStream} 接口而非裸流：带工具的请求会被
     * {@link ToolLoopSupport#wrap} 升级为「工具循环流」（模型调工具 → 框架执行 → 续轮），
     * 调用方拿到的可能是包装对象。回调链（onData / onReasoning / onToolCall / onComplete /
     * await …）两种情况下完全一致，节点代码无需区分。
     */
    @Override
    public ChatStream<OpenaiChatChunkResponse, OpenaiChatChunkResponse.ToolCallDelta> stream(ChatRequest chatRequest) {
        OpenaiChatRequest body = getProvider().toChatRequest(chatRequest);
        Map<String, Object> map = body.toMap();
        if (body.getStream() == null || body.getStream().equals(Boolean.FALSE)) {
            map.put("stream", true);
        }
        // 不开 include_usage，本家不会在流里回 token 统计
        if (!map.containsKey("stream_options")) {
            map.put("stream_options", Map.of("include_usage", true));
        }
        InputStream stream = getApi().chatCompletionStream(map);
        return ToolLoopSupport.wrap(new OpenaiChatStream(stream), chatRequest, this,
                OpenaiLlmClient::toToolCall, OpenaiChatChunkResponse::getContent);
    }

    /** 协议侧分片调用 → 循环层的中立形态；传入的已是流结束时的完整快照（arguments 拼好了） */
    private static ToolCall toToolCall(OpenaiChatChunkResponse.ToolCallDelta delta) {
        var function = delta.function();
        return new ToolCall(
                delta.id(),
                function == null ? null : function.name(),
                function == null ? null : function.arguments());
    }

    @Override
    public OpenaiModelsResponse models(Request<Model> request) {
        String responseBody = getApi().models(Map.of());
        return getProvider().parseModelsResponse(responseBody);
    }
}
