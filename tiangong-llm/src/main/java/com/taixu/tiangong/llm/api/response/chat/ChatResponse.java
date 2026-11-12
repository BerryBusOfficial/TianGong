package com.taixu.tiangong.llm.api.response.chat;

import com.taixu.tiangong.common.type.Chat;
import com.taixu.tiangong.llm.api.response.Response;

public interface ChatResponse<C extends Chat> extends Response<C> {

    String getContent();

}
