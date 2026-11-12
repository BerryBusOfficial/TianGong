package org.wutong.tiangong.llm.function;

import lombok.Data;
import org.wutong.tiangong.common.type.Model;
import org.wutong.tiangong.common.type.impl.Openai;
import org.wutong.tiangong.llm.api.request.Request;
import org.wutong.tiangong.llm.api.request.chat.ChatRequest;
import org.wutong.tiangong.llm.api.response.model.OpenaiModelsResponse;
import org.wutong.tiangong.llm.client.OpenaiLlmClient;
import org.wutong.tiangong.llm.event.result.OpenaiChatResult;
import org.wutong.tiangong.llm.message.HumanMessage;
import org.wutong.tiangong.llm.message.Message;
import org.wutong.tiangong.llm.options.chat.ChatOptions;
import org.wutong.tiangong.llm.provider.OpenaiProvider;
import reactor.util.annotation.Nullable;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Data
public class OpenaiClientFunction implements LlmClientFunction<Openai, OpenaiLlmClient> {

    private final OpenaiLlmClient client;

    private final Function<List<Message>, ChatRequest> chatRequestFunction;

    public OpenaiClientFunction(String baseUrl, String apiKey, String model, ChatOptions<Openai> chatOptions) {
        this.client = new OpenaiLlmClient(baseUrl, apiKey);
        this.chatRequestFunction = (msgs) -> ChatRequest.builder()
                .model(model)
                .messages(msgs)
                .chatOptions(chatOptions)
                //TODO: 自动扫描TOOL（记得加开关）
                .build();
    }

    @Override
    public OpenaiLlmClient getClient() {
        return client;
    }

    @Override
    public OpenaiProvider getProvider() {
        return client.getProvider();
    }

    @Override
    public OpenaiChatResult chat(String content) {
        return client.chat(chatRequestFunction.apply(List.of(new HumanMessage(content))));
    }

    @Override
    public OpenaiChatResult chat(List<String> session) {
        return client.chat(chatRequestFunction.apply(session.stream()
                .map(HumanMessage::new)
                .collect(Collectors.toList())
        ));
    }

    @Override
    public OpenaiModelsResponse models(@Nullable Request<Model> request) {
        return client.models(request);
    }
}
