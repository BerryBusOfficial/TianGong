package com.taixu.tiangong.core.llm;

import java.util.List;

/**
 * 结构化输出失败：没有 JSON、JSON 非法、或不符合 schema。<br>
 * 带原始文本与逐条校验错误，供修复循环精确反馈给模型，也供调用方排查。
 */
public class StructuredOutputException extends RuntimeException {

    private final List<String> errors;
    private final String rawText;

    public StructuredOutputException(String message, List<String> errors, String rawText, Throwable cause) {
        super(message, cause);
        this.errors = errors == null ? List.of() : List.copyOf(errors);
        this.rawText = rawText;
    }

    public List<String> errors() {
        return errors;
    }

    public String rawText() {
        return rawText;
    }
}
