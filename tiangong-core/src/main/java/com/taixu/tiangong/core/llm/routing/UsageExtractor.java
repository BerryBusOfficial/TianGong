package com.taixu.tiangong.core.llm.routing;

import com.taixu.tiangong.llm.api.response.chat.ChatResponse;
import com.taixu.tiangong.llm.api.response.chat.DeepseekChatResponse;
import com.taixu.tiangong.llm.api.response.chat.OllamaChatResponse;
import com.taixu.tiangong.llm.api.response.chat.OpenaiChatResponse;

/**
 * 从各家响应里提取 token 用量。<br>
 * provider 的 usage 形状不同（OpenAI/Deepseek 有 usage 对象，Ollama 给 prompt/eval 计数），
 * 这里只做一次归一化；账本、路由、观测都只认 {@link TokenUsage}。
 */
public final class UsageExtractor {

    private UsageExtractor() {
    }

    public static TokenUsage extract(ChatResponse<?> response) {
        if (response == null) {
            return TokenUsage.ZERO;
        }
        if (response instanceof OpenaiChatResponse openai && openai.usage() != null) {
            return new TokenUsage(openai.usage().promptTokens(),
                    openai.usage().completionTokens(), openai.usage().totalTokens());
        }
        if (response instanceof DeepseekChatResponse deepseek && deepseek.usage() != null) {
            return new TokenUsage(deepseek.usage().promptTokens(),
                    deepseek.usage().completionTokens(), deepseek.usage().totalTokens());
        }
        if (response instanceof OllamaChatResponse ollama) {
            long prompt = ollama.promptEvalCount() == null ? 0 : ollama.promptEvalCount();
            long completion = ollama.evalCount() == null ? 0 : ollama.evalCount();
            return new TokenUsage(prompt, completion, prompt + completion);
        }
        return TokenUsage.ZERO;
    }
}
