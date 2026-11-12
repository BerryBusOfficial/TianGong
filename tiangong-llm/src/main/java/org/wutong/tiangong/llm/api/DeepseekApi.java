package org.wutong.tiangong.llm.api;

public class DeepseekApi extends OpenaiApi {

    public DeepseekApi(String baseUrl) {
        super(baseUrl);
    }

    public DeepseekApi(String baseUrl, String apiKey) {
        super(baseUrl, apiKey);
    }

    @Override
    public String getChatCompletionEndpoint() {
        return "/chat/completions";
    }

    @Override
    public String getEmbeddingsEndpoint() {
        return UNSUPPORTED_INVOCATION;
    }

    @Override
    public String getModelsEndpoint() {
        return "/models";
    }

    @Override
    public String balance() {
        return "/user/balance";
    }
}
