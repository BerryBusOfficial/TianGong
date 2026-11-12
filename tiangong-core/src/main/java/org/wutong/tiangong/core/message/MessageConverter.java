package org.wutong.tiangong.core.message;

import org.wutong.tiangong.llm.message.Message;

public interface MessageConverter {

    Message toApi(ConversationMessage message);

    ConversationMessage fromApi(Message message);

}
