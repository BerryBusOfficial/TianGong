package org.wutong.tiangong.llm.api.request.embedding;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Builder;
import lombok.Getter;
import org.wutong.tiangong.common.serialize.duration.DurationDeserializer;
import org.wutong.tiangong.common.serialize.duration.DurationSerializer;
import org.wutong.tiangong.common.type.impl.Ollama;
import org.wutong.tiangong.llm.api.request.Request;

import java.time.Duration;
import java.util.List;

@Getter
@Builder
public class OllamaEmbeddingRequest implements Request<Ollama> {

    private final String model;
    private final List<String> input;
    private final Boolean truncate;
    private final Integer dimensions;

    @JsonSerialize(using = DurationSerializer.class)
    @JsonDeserialize(using = DurationDeserializer.class)
    private final Duration keepAlive;

}
