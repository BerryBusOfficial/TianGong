package org.wutong.tiangong.llm.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import org.wutong.tiangong.common.enums.ChatRoleType;

import java.util.List;

/**
 * 对话消息。<br>
 * <br>
 * <b>可序列化契约</b>：本类需支持 Jackson 往返（检查点状态快照依赖它，
 * 见 docs/checkpoint-contract.md A3）。因 {@code role}/{@code content} 为 {@code final}
 * 且不提供 setter，必须显式声明 {@link JsonCreator} —— 否则 Jackson 会报
 * {@code Cannot construct instance of Message (no Creators, like default constructor, exist)}，
 * 导致任何持有 {@code List<Message>} 的上下文无法从检查点还原。
 */
@Data
@ToString
@RequiredArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Message {

    private final ChatRoleType role;
    private final String content;
    private List<String> images;
    private String name;

    /**
     * Jackson 反序列化入口（检查点契约 A3）。<br>
     * 显式标注优于隐式构造器：Jackson 遇到 {@code @JsonCreator} 会优先采用，
     * 与 {@link RequiredArgsConstructor} 生成的 2 参构造器不冲突。
     */
    @JsonCreator
    public Message(@JsonProperty("role") ChatRoleType role,
                   @JsonProperty("content") String content,
                   @JsonProperty("images") List<String> images,
                   @JsonProperty("name") String name) {
        this.role = role;
        this.content = content;
        this.images = images;
        this.name = name;
    }

}
