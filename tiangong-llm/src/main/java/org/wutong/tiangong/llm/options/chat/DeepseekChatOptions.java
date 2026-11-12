package org.wutong.tiangong.llm.options.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;
import org.wutong.tiangong.annotations.annotation.framework.component.LlmOptions;
import org.wutong.tiangong.common.type.Chat;
import org.wutong.tiangong.common.type.impl.Deepseek;
import org.wutong.tiangong.llm.api.request.chat.DeepseekChatRequest.*;

import java.util.List;

@Getter
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
@LlmOptions(
        provider = Deepseek.class,
        model = Chat.class
)
public class DeepseekChatOptions implements ChatOptions<Deepseek> {

    private final Thinking thinking;
    private final ReasonEffort reasonEffort;
    private final Integer maxTokens;
    private final ResponseFormat responseFormat;
    private final List<String> stop;
    private final StreamOptions streamOptions;
    private final Integer topLogprobs;
    private final String userId;

    @Builder.Default
    private final ToolChoice toolChoice = ToolChoice.auto();
    @Builder.Default
    private final Double temperature = 1d;
    @Builder.Default
    private final Double topP = 1d;

}
