package org.wutong.tiangong.llm.message;

import org.wutong.tiangong.common.enums.ChatRoleType;

public class SystemMessage extends Message {

    public SystemMessage(String content, String name) {
        super(ChatRoleType.SYSTEM, content, null, name);
    }

    public SystemMessage(String content) {
        super(ChatRoleType.SYSTEM, content);
    }
}
