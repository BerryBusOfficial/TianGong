package com.taixu.tiangong.core.message;

import com.taixu.tiangong.llm.message.Message;

public interface MessageConverter {

    Message toApi(ConversationMessage message);

    ConversationMessage fromApi(Message message);

}
