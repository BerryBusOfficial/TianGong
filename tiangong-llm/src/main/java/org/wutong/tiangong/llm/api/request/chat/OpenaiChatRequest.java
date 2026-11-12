package org.wutong.tiangong.llm.api.request.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.*;
import org.wutong.tiangong.common.exception.SystemRuntimeException;
import org.wutong.tiangong.common.type.impl.Openai;
import org.wutong.tiangong.llm.api.request.Request;
import org.wutong.tiangong.llm.message.Message;

import java.util.List;
import java.util.Map;

/**
 * OpenAI 本家 {@code POST /v1/chat/completions} 请求体。<br>
 * 字段名走 JsonUtil 的全局 SNAKE_CASE，序列化时 null 自动省略，
 * 所以「推理模型才认的字段」不传就不会发出去。
 */
@Getter
@Builder
public class OpenaiChatRequest implements Request<Openai> {

    private final String model;
    private final List<Message> messages;

    /**
     * 推理强度。仅 o 系列 / gpt-5 等推理模型接受；普通模型（gpt-4o 等）
     * 传了会被拒，所以默认 null = 不发送。
     */
    private final ReasoningEffort reasoningEffort;

    /**
     * 输出详略，gpt-5 系列支持。
     */
    private final Verbosity verbosity;

    /** 新版字段：推理模型用它，和 {@code max_tokens} 不共存 */
    private final Integer maxCompletionTokens;
    /** 旧版字段：gpt-4o 及之前用它 */
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
    /** 允许一轮返回多个工具调用 */
    private final Boolean parallelToolCalls;
    /** 是否留存本次对话（用于 Responses / 评测） */
    private final Boolean store;

    private final Boolean logprobs;
    private final Integer topLogprobs;
    /** 终端用户标识，OpenAI 用它做滥用监测 */
    private final String user;

    @Getter
    @AllArgsConstructor
    public enum ReasoningEffort {
        MINIMAL("minimal"),
        LOW("low"),
        MEDIUM("medium"),
        HIGH("high"),
        ;

        @JsonValue
        private final String value;
    }

    @Getter
    @AllArgsConstructor
    public enum Verbosity {
        LOW("low"),
        MEDIUM("medium"),
        HIGH("high"),
        ;

        @JsonValue
        private final String value;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class ResponseFormat {

        @With
        private Format type = Format.TEXT;

        /** type = json_schema 时必填 */
        private JsonSchema jsonSchema;

        @Getter
        @AllArgsConstructor
        public enum Format {
            TEXT("text"),
            JSON_OBJECT("json_object"),
            JSON_SCHEMA("json_schema"),
            ;

            @JsonValue
            private final String value;
        }

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static final class JsonSchema {
            private String name;
            private boolean strict = true;
            private Map<String, Object> schema;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class StreamOptions {
        @With
        private boolean includeUsage = true;
    }

    /**
     * tool_choice：字符串（auto/none/required）或指定单个函数。<br>
     * 与 Deepseek 版同构（本家协议本来就是同一套），此处独立一份是为了
     * 不把 OpenAI 请求体绑死在 Deepseek 的内部类上。
     */
    public static final class ToolChoice {

        private final Object value;

        private ToolChoice(Object value) {
            this.value = value;
        }

        @JsonCreator
        public static ToolChoice fromJson(Object value) {
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
