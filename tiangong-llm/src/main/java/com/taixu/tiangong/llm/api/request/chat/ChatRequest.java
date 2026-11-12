package com.taixu.tiangong.llm.api.request.chat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;
import com.taixu.tiangong.common.type.Chat;
import com.taixu.tiangong.llm.api.request.Request;
import com.taixu.tiangong.llm.message.Message;
import com.taixu.tiangong.llm.options.chat.ChatOptions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
@Builder(toBuilder = true)   // 工具循环回放：复制原请求、只换 messages
@Jacksonized
public class ChatRequest implements Request<Chat> {

    private final String model;
    private final List<Message> messages;
    private final Boolean stream;

    private final List<Tool> tools;

    private final ChatOptions<?> chatOptions;

    @Getter
    @Builder
    @Jacksonized
    public static final class JsonSchema {

        @Builder.Default
        private final String type = "object";
        @Builder.Default
        private final Map<String, Property> properties = new LinkedHashMap<>();
        @Builder.Default
        private final List<String> required = List.of();
        @Builder.Default
        @JsonProperty("additionalProperties")
        private final Boolean additionalProperties = null;
        /**
         * 组合关键字 {@code oneOf}：表达「按某个字段分叉」的 schema。<br>
         * MCP 的 {@code firefly_query} 就是这种形态（不同 operation 带不同参数），
         * 没有它这类工具下发到模型时是残缺的 —— 模型不知道能填什么，只能编。<br>
         * ⚠ 同样要标 {@code @JsonProperty}：全局 mapper 是 SNAKE_CASE，
         * 不标会输出 {@code "one_of"}，协议不认。
         */
        @JsonProperty("oneOf")
        private final List<JsonSchema> oneOf;

        @Builder
        @Getter
        @Jacksonized
        public static final class Property {

            private final String type;
            private final String description;
            /**
             * ⚠ 协议里这个字段叫 {@code enum}，但 Java 的 {@code enum} 是关键字不能作字段名，
             * 所以这里叫 {@code enums} —— <b>必须显式标 {@code @JsonProperty("enum")}</b>：
             * Jackson 默认会输出 {@code "enums"}，协议不认，可选值等于一个都没下发。
             */
            @JsonProperty("enum")
            private final List<String> enums;
            private final JsonSchema items;
            /**
             * 嵌套对象：{@code type=object} 时挂子 schema，否则为 null。<br>
             * 没有它，MCP 那种多层结构的工具只能下发到第一层就被截断。<br>
             * 必填项放在子 schema 自己的 {@code required} 里 —— 协议中
             * {@code required} 属于 object schema 层级，不是属性层级。
             */
            private final JsonSchema properties;

            /**
             * record 风格访问器别名：框架里绝大多数 DTO 是 record（{@code x()}），
             * 只有这里是 lombok {@code @Getter}（{@code getX()}），按 record 习惯写会编译不过。
             */
            public String type() {
                return this.type;
            }

            public JsonSchema items() {
                return this.items;
            }

            public JsonSchema properties() {
                return this.properties;
            }
        }

        /**
         * 同上：record 风格访问器别名，两种写法都能用。
         */
        public Map<String, Property> properties() {
            return this.properties;
        }

        public List<String> required() {
            return this.required;
        }

        public List<JsonSchema> oneOf() {
            return this.oneOf;
        }
    }

    @Getter
    @Builder
    @Jacksonized
    public static final class Tool {
        @Builder.Default
        private final Type type = Type.FUNCTION;
        private final Function function;

        @Getter
        @AllArgsConstructor
        public enum Type {
            FUNCTION("function"),
            ;

            @JsonValue
            private final String value;
        }

        @Getter
        @Builder
        @Jacksonized
        public static final class Function {

            private final String name;

            private final String description;

            private final JsonSchema parameters;
        }
    }

}
