package com.taixu.tiangong.core.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import lombok.extern.slf4j.Slf4j;
import com.taixu.tiangong.annotations.annotation.framework.component.LlmClient;
import com.taixu.tiangong.annotations.annotation.framework.component.LlmOptions;
import com.taixu.tiangong.annotations.processor.registry.Registry;
import com.taixu.tiangong.common.exception.SystemRuntimeException;
import com.taixu.tiangong.common.util.JsonUtil;
import com.taixu.tiangong.core.config.ConfigHolder;
import com.taixu.tiangong.llm.api.request.chat.ChatRequest;
import com.taixu.tiangong.llm.api.request.embedding.EmbeddingRequest;
import com.taixu.tiangong.llm.message.Message;
import com.taixu.tiangong.llm.options.Options;
import com.taixu.tiangong.llm.options.chat.ChatOptions;
import com.taixu.tiangong.llm.options.embedding.EmbeddingOptions;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class LlmRegistry implements Registry<com.taixu.tiangong.llm.client.LlmClient<?, ?>> {

    /**
     * name → LlmClient 实例
     */
    private final Map<String, com.taixu.tiangong.llm.client.LlmClient<?, ?>> clients = new ConcurrentHashMap<>();
    /**
     * name → ModelConfig
     */
    private final Map<String, ModelConfig> configs = new ConcurrentHashMap<>();

    /**
     * "Provider简单名:ModelType简单名" → Options 类
     */
    private final Map<String, Class<? extends Options<?>>> optionTypes = new HashMap<>();
    /**
     * Provider简单名 → LlmClient 实现类
     */
    private final Map<String, Class<? extends com.taixu.tiangong.llm.client.LlmClient<?, ?>>> clientTypes = new HashMap<>();

    /**
     * 扫描注解 + 读取配置 + 创建客户端（启动时调用一次）
     */
    public void load() {
        scanAnnotations();
        // 用 getValueOrDefault：配置"从未加载"（ConfigHolder.config == null，嵌入式宿主/测试）
        // 与"路径缺失"两种情况都退化为 null，跳过客户端注册。
        // 旧写法走 getValue：配置从未加载时是 NPE（不是 SystemRuntimeException，catch 不住），
        // 单机路径因 TianGongApplication.run 总会先 ConfigLoader.load 而被掩盖。
        List<ModelConfig> models = ConfigHolder.getValueOrDefault(
                "agent.models", new TypeReference<>() {
                }, null);
        if (models == null || models.isEmpty()) {
            log.warn("未配置 agent.models，跳过 LLM 客户端注册");
            return;
        }

        for (ModelConfig cfg : models) {
            try {
                com.taixu.tiangong.llm.client.LlmClient<?, ?> client = createClient(cfg);
                register(cfg.getName(), client);
                configs.put(cfg.getName(), cfg);
                log.info("注册 LLM 客户端: {} (provider={}, model={})",
                        cfg.getName(), cfg.getProvider(), cfg.getModel());
            } catch (Exception e) {
                throw new SystemRuntimeException("创建 LLM 客户端失败: " + cfg.getName(), e);
            }
        }
    }

    /**
     * 通用注册：以类简单名为 key
     */
    @Override
    public void register(com.taixu.tiangong.llm.client.LlmClient<?, ?> element) {
        clients.put(element.getClass().getSimpleName(), element);
    }

    /**
     * 配置注册：以配置别名为 key（load() 使用）
     */
    public void register(String name, com.taixu.tiangong.llm.client.LlmClient<?, ?> element) {
        clients.put(name, element);
    }

    @Override
    public void unregister(String name) {
        clients.remove(name);
        configs.remove(name);
    }

    @Override
    public Optional<com.taixu.tiangong.llm.client.LlmClient<?, ?>> get(String name) {
        return Optional.ofNullable(clients.get(name));
    }

    @Override
    public Collection<com.taixu.tiangong.llm.client.LlmClient<?, ?>> values() {
        return clients.values();
    }

    @Override
    public boolean contains(String name) {
        return clients.containsKey(name);
    }

    @Override
    public int size() {
        return clients.size();
    }

    @Override
    public Iterator<com.taixu.tiangong.llm.client.LlmClient<?, ?>> iterator() {
        return clients.values().iterator();
    }

    /**
     * 构建 ChatRequest：model / chatOptions 自动从配置填充。
     * 手动传入的 ChatRequest 则完全覆盖配置（调用方自行构建）。
     */
    public ChatRequest buildChatRequest(String clientName, List<Message> messages) {
        return buildChatRequest(clientName, messages, null);
    }


    public ChatRequest buildChatRequest(String clientName, List<Message> messages, Boolean stream) {
        ModelConfig cfg = configs.get(clientName);
        if (cfg == null) {
            throw new SystemRuntimeException("未找到模型配置: " + clientName);
        }
        return ChatRequest.builder()
                .model(cfg.getModel())
                .messages(messages)
                .stream(stream)
                .chatOptions(bindChatOptions(cfg))
                .build();
    }

    public ChatRequest buildChatRequest(String clientName, List<Message> messages,
                                        Boolean stream, List<ChatRequest.Tool> tools) {
        ModelConfig cfg = configs.get(clientName);
        if (cfg == null) throw new SystemRuntimeException("未找到模型配置: " + clientName);
        return ChatRequest.builder()
                .model(cfg.getModel())
                .messages(messages)
                .stream(stream)
                .tools(tools)                  // ← 新增
                .chatOptions(bindChatOptions(cfg))
                .build();
    }

    /**
     * 构建 EmbeddingRequest：model / options 自动从配置填充
     */
    public EmbeddingRequest buildEmbeddingRequest(String clientName, List<String> input) {
        return buildEmbeddingRequest(clientName, input, null);
    }

    /**
     * 显式覆盖 options
     */
    public EmbeddingRequest buildEmbeddingRequest(String clientName, List<String> input, EmbeddingOptions<?> options) {
        ModelConfig cfg = configs.get(clientName);
        if (cfg == null) {
            throw new SystemRuntimeException("未找到模型配置: " + clientName);
        }
        return new EmbeddingRequest(
                cfg.getModel(),
                input,
                options != null ? options : bindEmbeddingOptions(cfg)
        );
    }

    /**
     * 将配置的 embedding_options 段绑定为具体 Options 实例
     */
    private EmbeddingOptions<?> bindEmbeddingOptions(ModelConfig cfg) {
        if (cfg.getEmbeddingOptions() == null || cfg.getEmbeddingOptions().isEmpty()) {
            return null;
        }
        String key = cfg.getProvider().toLowerCase() + ":" + "Embedding".toLowerCase();
        Class<? extends Options<?>> optionsClass = optionTypes.get(key);
        if (optionsClass == null) {
            log.warn("Embedding: 未找到 {} 对应的 Options 类，跳过绑定", key);
            return null;
        }
        return (EmbeddingOptions<?>) JsonUtil.convertToBean(cfg.getEmbeddingOptions(), optionsClass);
    }

    // ==================== 内部 ====================

    /**
     * 根据配置实例化客户端：约定所有 LlmClient 提供 (String baseUrl, String apiKey) 构造器
     */
    private com.taixu.tiangong.llm.client.LlmClient<?, ?> createClient(ModelConfig cfg) throws Exception {
        Class<? extends com.taixu.tiangong.llm.client.LlmClient<?, ?>> clientClass = clientTypes.get(cfg.getProvider().toLowerCase());
        if (clientClass == null) {
            throw new SystemRuntimeException("未注册的 provider: " + cfg.getProvider());
        }
        return clientClass.getConstructor(String.class, String.class)
                .newInstance(cfg.getBaseUrl(), cfg.getApiKey());
    }

    /**
     * 将配置的 chat-options 段绑定为具体 Options 实例（手动未传时使用）
     */
    private ChatOptions<?> bindChatOptions(ModelConfig cfg) {
        if (cfg.getChatOptions() == null || cfg.getChatOptions().isEmpty()) {
            return null;
        }
        String key = cfg.getProvider().toLowerCase() + ":" + "Chat".toLowerCase();
        Class<? extends Options<?>> optionsClass = optionTypes.get(key);
        if (optionsClass == null) {
            log.warn("未找到 {} 对应的 Options 类，跳过绑定", key);
            return null;
        }
        return (ChatOptions<?>) JsonUtil.convertToBean(cfg.getChatOptions(), optionsClass);
    }

    /**
     * 扫描 @LlmClient / @LlmOptions，建立映射表
     */
    @SuppressWarnings("unchecked")
    private void scanAnnotations() {
        try (ScanResult scan = new ClassGraph()
                .acceptPackages("com.taixu")
                .enableAllInfo()
                .scan()) {

            for (ClassInfo info : scan.getClassesWithAnnotation(LlmClient.class.getName())) {
                Class<?> clazz = info.loadClass();
                LlmClient ann = clazz.getAnnotation(LlmClient.class);
                clientTypes.put(ann.provider().getSimpleName().toLowerCase(), (Class<? extends com.taixu.tiangong.llm.client.LlmClient<?, ?>>) clazz);
            }

            for (ClassInfo info : scan.getClassesWithAnnotation(LlmOptions.class.getName())) {
                Class<?> clazz = info.loadClass();
                LlmOptions ann = clazz.getAnnotation(LlmOptions.class);
                String key = ann.provider().getSimpleName().toLowerCase() + ":" + ann.model().getSimpleName().toLowerCase();
                optionTypes.put(key, (Class<? extends Options<?>>) clazz);
            }
        }
    }

}
