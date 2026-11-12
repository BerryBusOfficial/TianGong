package org.wutong.tiangong.llm.api.response.chat.chunk;

import org.wutong.tiangong.common.type.Chat;
import org.wutong.tiangong.llm.api.response.ChunkResponse;

public interface ChatChunkResponse<C extends Chat> extends ChunkResponse<C> {

    String getContent();

}
