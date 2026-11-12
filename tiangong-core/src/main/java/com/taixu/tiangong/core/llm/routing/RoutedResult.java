package com.taixu.tiangong.core.llm.routing;

import com.taixu.tiangong.llm.api.response.chat.ChatResponse;

import java.util.List;

/**
 * 路由执行结果：最终采用的模型 + 响应 + 完整尝试链（含被跳过的候选）。<br>
 * 保留尝试链是为了回答「为什么这次走了 pro 而不是 flash」，而不是只看最终模型名。
 */
public record RoutedResult(
        String model,
        ChatResponse<?> response,
        List<RoutedAttempt> attempts
) {
    public RoutedResult {
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
    }
}
