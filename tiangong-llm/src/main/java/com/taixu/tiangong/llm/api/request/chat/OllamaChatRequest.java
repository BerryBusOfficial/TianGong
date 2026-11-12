package com.taixu.tiangong.llm.api.request.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import com.taixu.tiangong.common.serialize.duration.DurationDeserializer;
import com.taixu.tiangong.common.serialize.duration.DurationSerializer;
import com.taixu.tiangong.common.type.impl.Ollama;
import com.taixu.tiangong.llm.api.request.Request;
import com.taixu.tiangong.llm.entity.Option;
import com.taixu.tiangong.llm.message.Message;

import java.time.Duration;
import java.util.List;

@Getter
@Builder
public class OllamaChatRequest implements Request<Ollama> {

    private final String model;
    private final List<Message> messages;
    private final List<ChatRequest.Tool> tools;
    private final ChatRequest.JsonSchema format;
    private final Option options;

    @Builder.Default
    private final Boolean stream = false;

    private final Think think;

    @JsonSerialize(using = DurationSerializer.class)
    @JsonDeserialize(using = DurationDeserializer.class)
    private final Duration keepAlive;

    private final Boolean logprobs;
    private final Integer topLogprobs;


    @Data
    public static final class Think {

        private final Boolean thinking;
        private final String thinkingIntensity;

        public Think(Boolean thinking) {
            this.thinking = thinking;
            this.thinkingIntensity = null;
        }

        public Think(String thinkingIntensity) {
            if (thinkingIntensity == null) {
                this.thinking = false;
                this.thinkingIntensity = null;
            } else {
                this.thinking = true;
                this.thinkingIntensity = thinkingIntensity;
            }
        }

        /**
         * 反序列化入口。<br>
         * <b>为什么必须显式补</b>：这个类原本只有 {@code @JsonValue}、没有任何反序列化入口，
         * 于是 {@code chat_options.think} 在配置绑定与请求体反序列化时都不可靠 ——
         * 典型症状是「yml 里写了 think: false，模型照样输出思考过程」。
         * Ollama 协议里 think 既可以是布尔、也可以是强度字符串（high / medium / low），这里两种都收。
         */
        @JsonCreator
        static Think fromValue(Object value) {
            if (value == null) {
                return new Think(false);
            }
            if (value instanceof Boolean bool) {
                return new Think(bool);
            }
            String text = value.toString().trim();
            if (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false")) {
                return new Think(Boolean.valueOf(text));
            }
            return new Think(text);
        }

        @JsonValue
        public Object getValue() {
            return thinkingIntensity != null ? thinkingIntensity : thinking;
        }
    }

}
