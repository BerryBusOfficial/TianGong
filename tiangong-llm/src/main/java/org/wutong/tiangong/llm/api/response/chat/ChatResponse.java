package org.wutong.tiangong.llm.api.response.chat;

import org.wutong.tiangong.common.type.Chat;
import org.wutong.tiangong.llm.api.response.Response;

public interface ChatResponse<C extends Chat> extends Response<C> {

    String getContent();

}
