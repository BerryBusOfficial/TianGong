package com.taixu.tiangong.llm.api.response.chat.chunk;

import com.taixu.tiangong.common.type.Chat;
import com.taixu.tiangong.llm.api.response.ChunkResponse;

public interface ChatChunkResponse<C extends Chat> extends ChunkResponse<C> {

    String getContent();

}
