package org.wutong.tiangong.llm.client;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.wutong.tiangong.common.type.Model;
import org.wutong.tiangong.common.type.impl.Deepseek;
import org.wutong.tiangong.llm.api.Api;
import org.wutong.tiangong.llm.api.DeepseekApi;
import org.wutong.tiangong.llm.api.request.Request;
import org.wutong.tiangong.llm.api.request.chat.ChatRequest;
import org.wutong.tiangong.llm.api.request.chat.DeepseekChatRequest;
import org.wutong.tiangong.llm.api.response.chat.chunk.DeepseekChatChunkResponse;
import org.wutong.tiangong.llm.api.response.model.DeepseekModelsResponse;
import org.wutong.tiangong.llm.event.result.DeepseekChatResult;
import org.wutong.tiangong.llm.event.stream.ChatStream;
import org.wutong.tiangong.llm.event.stream.DeepseekChatStream;
import org.wutong.tiangong.llm.event.stream.ToolCall;
import org.wutong.tiangong.llm.event.stream.ToolLoopSupport;
import org.wutong.tiangong.llm.provider.DeepseekProvider;

import java.io.InputStream;
import java.util.Map;

@Data
@Slf4j
@org.wutong.tiangong.annotations.annotation.framework.component.LlmClient(provider = Deepseek.class)
public class DeepseekLlmClient implements org.wutong.tiangong.llm.client.LlmClient<Deepseek, DeepseekProvider> {

    private final String baseUrl;
    private final String apikey;

    private final DeepseekProvider provider;
    private final DeepseekApi deepseekApi;

    public DeepseekLlmClient() {
        this(System.getenv("DEEPSEEK_API_KEY"));
    }

    public DeepseekLlmClient(String apiKey) {
        this("https://api.deepseek.com", apiKey);
    }

    public DeepseekLlmClient(String baseUrl, String apiKey) {
        this.apikey = apiKey;
        this.baseUrl = baseUrl;
        this.provider = new DeepseekProvider();
        this.deepseekApi = new DeepseekApi(baseUrl, apiKey);
    }

    @Override
    public DeepseekProvider getProvider() {
        return provider;
    }

    @Override
    public Api getApi() {
        return deepseekApi;
    }

    @Override
    public DeepseekChatResult chat(ChatRequest chatRequest) {
        try {
            DeepseekChatRequest body = getProvider().toChatRequest(chatRequest);
            Map<String, Object> map = body.toMap();
            if (body.getStream() == null || body.getStream().equals(Boolean.TRUE)) {
                map.put("stream", false);
            }
            String responseBody = getApi().chatCompletion(map);
            return new DeepseekChatResult(getProvider().parseChatResponse(responseBody));
        } catch (Exception e) {
            return new DeepseekChatResult(null, e);   // 错误进 ChatResult：onError 回调 / join 抛出
        }
    }

    /**
     * 流式对话。<br>
     * 返回类型是 {@link ChatStream} 接口而非裸流：带工具的请求会被
     * {@link ToolLoopSupport#wrap} 升级为「工具循环流」（模型调工具 → 框架执行 → 续轮），
     * 调用方拿到的可能是包装对象。
     */
    @Override
    public ChatStream<DeepseekChatChunkResponse, DeepseekChatChunkResponse.ToolCallDelta> stream(ChatRequest chatRequest) {
        DeepseekChatRequest body = getProvider().toChatRequest(chatRequest);
        Map<String, Object> map = body.toMap();
        if (body.getStream() == null || body.getStream().equals(Boolean.FALSE)) {
            map.put("stream", true);
        }
        InputStream stream = getApi().chatCompletionStream(map);
        return ToolLoopSupport.wrap(new DeepseekChatStream(stream), chatRequest, this, DeepseekLlmClient::toToolCall);
    }

    /** 协议侧分片调用 → 循环层的中立形态；传入的已是流结束时的完整快照（arguments 拼好了） */
    private static ToolCall toToolCall(DeepseekChatChunkResponse.ToolCallDelta delta) {
        var function = delta.function();
        return new ToolCall(
                delta.id(),
                function == null ? null : function.name(),
                function == null ? null : function.arguments());
    }

    @Override
    public DeepseekModelsResponse models(Request<Model> request) {
        String responseBody = getApi().models(Map.of());
        return getProvider().parseModelsResponse(responseBody);
    }
}
