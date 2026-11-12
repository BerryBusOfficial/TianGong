package org.wutong.tiangong.llm.options.embedding;

import org.wutong.tiangong.common.type.Embedding;
import org.wutong.tiangong.common.type.ProviderType;
import org.wutong.tiangong.llm.options.Options;

public interface EmbeddingOptions<T extends ProviderType> extends Options<Embedding> {
}
