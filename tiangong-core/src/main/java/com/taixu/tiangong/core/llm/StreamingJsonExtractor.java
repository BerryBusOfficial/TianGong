package com.taixu.tiangong.core.llm;

import java.util.Optional;

/**
 * 流式 JSON 提取器：边收 delta 边判断「完整 JSON 到了没有」。<br>
 * <br>
 * 只做提取，不做 schema 校验 —— 校验发生在完整对象之后。这样 UI 可以在
 * {@code structured} 事件到达时一次性渲染结构化卡片，而不必把正文里的半截 JSON
 * 先显示出来再清理（那正是 {@code <<<REPORT_DRAFT>>>} 哨兵方案留下的闪烁）。
 */
public final class StreamingJsonExtractor {

    private final StringBuilder buffer = new StringBuilder();

    public void accept(String delta) {
        if (delta != null && !delta.isEmpty()) {
            buffer.append(delta);
        }
    }

    /**
     * 尝试提取第一个完整 JSON；没有则返回 empty，缓冲区保持不变。
     * 提取成功后，已消费部分从缓冲区移除（支持一段正文里连续多个对象）。
     */
    public Optional<String> tryExtract() {
        String text = buffer.toString();
        String json = StructuredOutputParser.extractJson(text);
        if (json == null) {
            return Optional.empty();
        }
        int end = text.indexOf(json) + json.length();
        buffer.delete(0, end);
        return Optional.of(json);
    }

    public String buffered() {
        return buffer.toString();
    }

    public void reset() {
        buffer.setLength(0);
    }
}
