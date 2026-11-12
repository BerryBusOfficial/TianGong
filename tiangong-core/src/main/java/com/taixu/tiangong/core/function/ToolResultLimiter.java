package com.taixu.tiangong.core.function;

/**
 * 工具结果大小上限：防止模型/日志被超长结果拖垮。<br>
 * 截断而不是拒绝——工具已经执行，拒绝结果会让模型看不到任何信息；保留尾部摘要说明总长度。
 */
public final class ToolResultLimiter {

    public static final int DEFAULT_MAX_CHARS = 32_000;

    private ToolResultLimiter() {
    }

    public static String limit(String text) {
        return limit(text, DEFAULT_MAX_CHARS);
    }

    public static String limit(String text, int maxChars) {
        if (text == null || text.length() <= Math.max(1, maxChars)) {
            return text;
        }
        int limit = Math.max(1, maxChars);
        int omitted = text.length() - limit;
        return text.substring(0, limit)
                + "\n...[tool result truncated: omitted " + omitted
                + " chars, total " + text.length() + "]";
    }
}
