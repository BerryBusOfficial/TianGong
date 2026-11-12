package com.taixu.tiangong.core.message;

import com.taixu.tiangong.common.enums.ChatRoleType;

public interface ConversationMessage {

    String getId();

    ChatRoleType getRole();

    String getContent();

    long getTimestamp();

}
