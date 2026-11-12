package org.wutong.tiangong.llm.options.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;
import org.wutong.tiangong.annotations.annotation.framework.component.LlmOptions;
import org.wutong.tiangong.common.serialize.duration.DurationDeserializer;
import org.wutong.tiangong.common.serialize.duration.DurationSerializer;
import org.wutong.tiangong.common.type.Chat;
import org.wutong.tiangong.common.type.impl.Ollama;
import org.wutong.tiangong.llm.api.request.chat.ChatRequest;
import org.wutong.tiangong.llm.api.request.chat.OllamaChatRequest;
import org.wutong.tiangong.llm.entity.Option;

import java.time.Duration;

@Getter
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
@LlmOptions(provider = Ollama.class, model = Chat.class)
public class OllamaChatOption implements ChatOptions<Ollama> {

    private final OllamaChatRequest.Think think;
    @JsonSerialize(using = DurationSerializer.class)
    @JsonDeserialize(using = DurationDeserializer.class)
    private final Duration keepAlive;
    private final Boolean logprobs;
    private final Integer topLogprobs;
    private final Option options;
    private ChatRequest.JsonSchema format;

}
