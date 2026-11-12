package org.wutong.tiangong.llm.api;

import java.util.Map;

public record ChatMetadata(
        String object,
        long created,
        String model,
        Usage usage
) {
    public record Usage(
            long promptTokens,
            long completionTokens,
            long totalTokens,
            Map<String, Object> promptTokensDetails,
            Map<String, Object> completionTokensDetails
    ) {
    }
}
