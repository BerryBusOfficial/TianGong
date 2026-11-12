package org.wutong.tiangong.llm.function;

import lombok.Data;
import org.wutong.tiangong.common.type.Model;
import org.wutong.tiangong.common.type.impl.Deepseek;
import org.wutong.tiangong.llm.api.request.Request;
import org.wutong.tiangong.llm.api.request.chat.ChatRequest;
import org.wutong.tiangong.llm.api.response.model.DeepseekModelsResponse;
import org.wutong.tiangong.llm.client.DeepseekLlmClient;
import org.wutong.tiangong.llm.event.result.DeepseekChatResult;
import org.wutong.tiangong.llm.message.HumanMessage;
import org.wutong.tiangong.llm.message.Message;
import org.wutong.tiangong.llm.options.chat.ChatOptions;
import org.wutong.tiangong.llm.provider.DeepseekProvider;
import reactor.util.annotation.Nullable;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Data
public class DeepseekClientFunction implements LlmClientFunction<Deepseek, DeepseekLlmClient> {

    private final DeepseekLlmClient client;

    private final Function<List<Message>, ChatRequest> chatRequestFunction;

    public DeepseekClientFunction(String baseUrl, String apiKey, String model, ChatOptions<Deepseek> chatOptions) {
        this.client = new DeepseekLlmClient(baseUrl, apiKey);
        this.chatRequestFunction = (msgs) -> ChatRequest.builder()
                .model(model)
                .messages(msgs)
                .chatOptions(chatOptions)
                //TODO: 自动扫描TOOL（记得加开关）
                .build();
    }

    @Override
    public DeepseekLlmClient getClient() {
        return client;
    }

    @Override
    public DeepseekProvider getProvider() {
        return client.getProvider();
    }

    @Override
    public DeepseekChatResult chat(String content) {
        return client.chat(chatRequestFunction.apply(List.of(new HumanMessage(content))));
    }

    @Override
    public DeepseekChatResult chat(List<String> session) {
        return client.chat(chatRequestFunction.apply(session.stream()
                .map(HumanMessage::new)
                .collect(Collectors.toList())
        ));
    }

    @Override
    public DeepseekModelsResponse models(@Nullable Request<Model> request) {
        return client.models(request);
    }
}
