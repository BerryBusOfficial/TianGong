package com.taixu.tiangong.llm.options.embedding;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;
import com.taixu.tiangong.annotations.annotation.framework.component.LlmOptions;
import com.taixu.tiangong.common.serialize.duration.DurationDeserializer;
import com.taixu.tiangong.common.serialize.duration.DurationSerializer;
import com.taixu.tiangong.common.type.Embedding;
import com.taixu.tiangong.common.type.impl.Ollama;

import java.time.Duration;

@Getter
@Builder
@Jacksonized
@LlmOptions(provider = Ollama.class, model = Embedding.class)
public class OllamaEmbeddingOptions implements EmbeddingOptions<Ollama> {

    private final Boolean truncate;
    private final Integer dimensions;

    @JsonSerialize(using = DurationSerializer.class)
    @JsonDeserialize(using = DurationDeserializer.class)
    private final Duration keepAlive;

}
