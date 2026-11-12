package com.taixu.tiangong.llm.options.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;
import com.taixu.tiangong.annotations.annotation.framework.component.LlmOptions;
import com.taixu.tiangong.common.type.Chat;
import com.taixu.tiangong.common.type.impl.Openai;
import com.taixu.tiangong.llm.api.request.chat.OpenaiChatRequest.*;

import java.util.List;

/**
 * agent.yml 里 {@code chat-options} 段的绑定目标（key = "openai:chat"）。<br>
 * 全部字段可空：不写就不下发，避免把 gpt-4o 不认的推理参数硬塞给本家。
 */
@Getter
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
@LlmOptions(
        provider = Openai.class,
        model = Chat.class
)
public class OpenaiChatOptions implements ChatOptions<Openai> {

    private final ReasoningEffort reasoningEffort;
    private final Verbosity verbosity;
    private final Integer maxCompletionTokens;
    private final Integer maxTokens;
    private final ResponseFormat responseFormat;
    private final List<String> stop;
    private final StreamOptions streamOptions;
    private final Boolean parallelToolCalls;
    private final Boolean store;
    private final Boolean logprobs;
    private final Integer topLogprobs;
    private final String user;

    @Builder.Default
    private final ToolChoice toolChoice = ToolChoice.auto();
    @Builder.Default
    private final Double temperature = 1d;
    @Builder.Default
    private final Double topP = 1d;

}
