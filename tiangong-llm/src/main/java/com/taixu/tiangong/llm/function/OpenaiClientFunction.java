package com.taixu.tiangong.llm.function;

import lombok.Data;
import com.taixu.tiangong.common.type.Model;
import com.taixu.tiangong.common.type.impl.Openai;
import com.taixu.tiangong.llm.api.request.Request;
import com.taixu.tiangong.llm.api.request.chat.ChatRequest;
import com.taixu.tiangong.llm.api.response.model.OpenaiModelsResponse;
import com.taixu.tiangong.llm.client.OpenaiLlmClient;
import com.taixu.tiangong.llm.event.result.OpenaiChatResult;
import com.taixu.tiangong.llm.message.HumanMessage;
import com.taixu.tiangong.llm.message.Message;
import com.taixu.tiangong.llm.options.chat.ChatOptions;
import com.taixu.tiangong.llm.provider.OpenaiProvider;
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
