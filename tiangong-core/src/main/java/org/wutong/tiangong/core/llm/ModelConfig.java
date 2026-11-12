package org.wutong.tiangong.core.llm;

import lombok.Data;

import java.util.Map;


@Data
public class ModelConfig {

    private String name;                       // 配置别名，LlmRegistry.get(name) 用
    private String provider;                   // "deepseek" / "ollama"
    private String model;                      // 模型名
    private String baseUrl;
    private String apiKey;
    private Map<String, Object> chatOptions;       // 可选
    private Map<String, Object> embeddingOptions;  // 可选

}
