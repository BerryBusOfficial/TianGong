package com.taixu.tiangong.llm.message;

import lombok.Data;
import lombok.EqualsAndHashCode;
import com.taixu.tiangong.common.enums.ChatRoleType;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class HumanMessage extends Message {

    public HumanMessage(String content) {
        super(ChatRoleType.USER, content);
    }

    public HumanMessage(String content, String name) {
        super(ChatRoleType.USER, content, null, name);
    }

    public HumanMessage(String content, List<String> images) {
        super(ChatRoleType.USER, content, images, null);
    }

    public HumanMessage(String content, List<String> images, String name) {
        super(ChatRoleType.USER, content, images, name);
    }
}
