package com.taixu.tiangong.core.llm.routing;

import com.taixu.tiangong.llm.event.stream.ToolCall;

import java.util.List;

/**
 * 流式路由结果：最终候选、正文、工具调用、用量、尝试链。<br>
 * 事件已经实时写入真实事件流（若绑定了），本结果只供调用方做落库/日志/断言。
 */
public record RoutedStreamResult(
        String model,
        String content,
        List<ToolCall> toolCalls,
        TokenUsage usage,
        List<RoutedAttempt> attempts
) {
    public RoutedStreamResult {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        usage = usage == null ? TokenUsage.ZERO : usage;
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
    }
}
