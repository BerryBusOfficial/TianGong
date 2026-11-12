package com.taixu.tiangong.llm.message;

import com.taixu.tiangong.common.enums.ChatRoleType;

public class SystemMessage extends Message {

    public SystemMessage(String content, String name) {
        super(ChatRoleType.SYSTEM, content, null, name);
    }

    public SystemMessage(String content) {
        super(ChatRoleType.SYSTEM, content);
    }
}
