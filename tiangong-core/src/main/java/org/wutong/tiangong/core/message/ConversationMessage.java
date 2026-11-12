package org.wutong.tiangong.core.message;

import org.wutong.tiangong.common.enums.ChatRoleType;

public interface ConversationMessage {

    String getId();

    ChatRoleType getRole();

    String getContent();

    long getTimestamp();

}
