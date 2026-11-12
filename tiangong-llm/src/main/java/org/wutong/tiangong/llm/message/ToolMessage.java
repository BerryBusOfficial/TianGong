package org.wutong.tiangong.llm.message;


import lombok.Data;
import lombok.EqualsAndHashCode;
import org.wutong.tiangong.common.enums.ChatRoleType;

@Data
@EqualsAndHashCode(callSuper = true)
public class ToolMessage extends Message {

    private final String toolCallId;

    public ToolMessage(String content, String toolCallId) {
        super(ChatRoleType.TOOL, content);
        this.toolCallId = toolCallId;
    }

}
