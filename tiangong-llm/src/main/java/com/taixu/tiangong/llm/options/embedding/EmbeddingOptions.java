package com.taixu.tiangong.llm.options.embedding;

import com.taixu.tiangong.common.type.Embedding;
import com.taixu.tiangong.common.type.ProviderType;
import com.taixu.tiangong.llm.options.Options;

public interface EmbeddingOptions<T extends ProviderType> extends Options<Embedding> {
}
