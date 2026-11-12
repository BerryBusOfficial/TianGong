package com.taixu.tiangong.llm.api.request.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.*;
import com.taixu.tiangong.common.exception.SystemRuntimeException;
import com.taixu.tiangong.common.type.impl.Deepseek;
import com.taixu.tiangong.llm.api.request.Request;
import com.taixu.tiangong.llm.message.Message;

import java.util.List;
import java.util.Map;

@Getter
@Builder
public class DeepseekChatRequest implements Request<Deepseek> {

    private final String model;
    private final List<Message> messages;
    private final Thinking thinking;
    private final ReasonEffort reasonEffort;
    private final Integer maxTokens;
    private final ResponseFormat responseFormat;
    private final List<String> stop;
    private final Boolean stream;
    private final StreamOptions streamOptions;
    @Builder.Default
    private final Double temperature = 1d;
    @Builder.Default
    private final Double topP = 1d;
    private final List<ChatRequest.Tool> tools;
    @Builder.Default
    private final ToolChoice toolChoice = ToolChoice.auto();
    private final Integer topLogprobs;
    private final String userId;

    @Getter
    @AllArgsConstructor
    public enum ReasonEffort {
        HIGH("high"),
        MAX("max"),
        ;

        @JsonValue
        private final String value;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class Thinking {

        @With
        private ThinkingStatus type = ThinkingStatus.ENABLED;

        @Getter
        @AllArgsConstructor
        public enum ThinkingStatus {
            ENABLED("enabled"),
            DISABLED("disabled");

            @JsonValue
            private final String value;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class ResponseFormat {

        @With
        private Format type = Format.TEXT;

        @Getter
        @AllArgsConstructor
        public enum Format {
            TEXT("text"),
            JSON_OBJECT("json_object"),
            ;

            @JsonValue
            private final String value;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class StreamOptions {
        @With
        private boolean includeUsage = true;
    }

    public static final class ToolChoice {

        private final Object value;

        private ToolChoice(Object value) {
            this.value = value;
        }

        @JsonCreator
        public static ToolChoice fromJson(Object value) {
            // 情况1：字符串（如 "auto", "none", "required"）
            if (value instanceof String str) {
                if ("auto".equalsIgnoreCase(str)) {
                    return auto();
                }
                if ("none".equalsIgnoreCase(str)) {
                    return none();
                }
                if ("required".equalsIgnoreCase(str)) {
                    return required();
                }
                throw new SystemRuntimeException("Unknown tool_choice string: " + str);
            }

            // 情况2：对象（如 {"type":"function", "function":{"name":"xxx"}}）
            if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) value;
                String type = (String) map.get("type");
                if ("function".equals(type)) {
                    @SuppressWarnings("unchecked")
                    Map<String, String> funcMap = (Map<String, String>) map.get("function");
                    if (funcMap != null && funcMap.containsKey("name")) {
                        return function(funcMap.get("name"));
                    }
                }
                throw new SystemRuntimeException("Invalid function tool_choice: " + value);
            }

            throw new SystemRuntimeException("Invalid tool_choice value: " + value);
        }

        public static ToolChoice auto() {
            return new ToolChoice(Type.AUTO);
        }

        public static ToolChoice required() {
            return new ToolChoice(Type.REQUIRED);
        }

        public static ToolChoice none() {
            return new ToolChoice(Type.NONE);
        }

        public static ToolChoice function(String name) {
            return new ToolChoice(
                    FunctionChoice.builder()
                            .function(new FunctionChoice.Function(name))
                            .build()
            );
        }

        @JsonValue
        public Object value() {
            return value;
        }

        @Getter
        @AllArgsConstructor
        public enum Type {
            AUTO("auto"),
            NONE("none"),
            REQUIRED("required"),
            ;

            @JsonValue
            private final String value;
        }

        @Getter
        @Builder
        private static class FunctionChoice {
            @Builder.Default
            private final String type = "function";
            private final Function function;

            @Data
            private static class Function {
                private final String name;
            }
        }
    }

}
