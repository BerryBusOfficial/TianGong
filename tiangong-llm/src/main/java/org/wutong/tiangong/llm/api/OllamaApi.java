package org.wutong.tiangong.llm.api;

public class OllamaApi extends OpenaiApi {

    public OllamaApi() {
        super("http://localhost:11434", "ollama");
    }

    public OllamaApi(String baseUrl) {
        super(baseUrl);
    }

    public OllamaApi(String baseUrl, String apiKey) {
        super(baseUrl, apiKey);
    }

    @Override
    public String getGenerateEndpoint() {
        return "/api/generate";
    }

    @Override
    public String getChatCompletionEndpoint() {
        return "/api/chat";
    }

    @Override
    public String getEmbeddingsEndpoint() {
        return "/api/embed";
    }

    @Override
    public String getModelsEndpoint() {
        return "/api/tags";
    }


}
