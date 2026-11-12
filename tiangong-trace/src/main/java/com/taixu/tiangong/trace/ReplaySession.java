package com.taixu.tiangong.trace;

/**
 * golden trace 回放：按记录顺序逐条比对 prompt hash、结果摘要与工具调用。<br>
 * <br>
 * 这是<b>校验回放</b>，不是工作流重执行：它回答「这次改动是否让同一 prompt 的结果/工具链
 * 发生了变化」。要从失败点继续执行，仍需检查点状态（{@code CheckpointService}），
 * 本类不假装能凭 trace 还原上下文。
 */
public final class ReplaySession {

    private final String runId;
    private final java.util.List<TraceEvent> timeline;
    private int index;

    public ReplaySession(TraceStore store, String runId) {
        this.runId = runId;
        this.timeline = store == null ? java.util.List.of() : store.timeline(runId);
    }

    public String runId() {
        return runId;
    }

    public int remaining() {
        return timeline.size() - index;
    }

    public java.util.Optional<TraceEvent> peek() {
        return index < timeline.size()
                ? java.util.Optional.of(timeline.get(index))
                : java.util.Optional.empty();
    }

    public TraceEvent next() {
        if (index >= timeline.size()) {
            throw new ReplayMismatchException("回放 [" + runId + "] 已结束，但仍有事件被消费");
        }
        return timeline.get(index++);
    }

    /** 下一条必须是模型调用，且 prompt hash 与当前 messages 一致 */
    public LlmTraceRecord expectLlm(java.util.List<com.taixu.tiangong.llm.message.Message> messages) {
        TraceEvent event = next();
        if (!(event instanceof LlmTraceRecord record)) {
            throw new ReplayMismatchException(
                    "期望 LLM 事件，实际是 " + event.getClass().getSimpleName());
        }
        String actualHash = PromptHasher.hash(messages);
        if (!actualHash.equals(record.promptHash())) {
            throw new ReplayMismatchException(
                    "prompt hash 不一致: recorded=" + record.promptHash()
                            + ", actual=" + actualHash);
        }
        return record;
    }

    /** 结果摘要必须与记录一致（都经过同一套脱敏/截断） */
    public void verifyResult(LlmTraceRecord record, String actualResult) {
        String actualPreview = TextRedactor.redact(actualResult, 800);
        if (!actualPreview.equals(record.resultPreview())) {
            throw new ReplayMismatchException(
                    "结果摘要不一致: recorded=[" + record.resultPreview()
                            + "], actual=[" + actualPreview + "]");
        }
    }

    /** 下一条必须是同名的工具调用 */
    public ToolTraceRecord expectTool(String toolName) {
        TraceEvent event = next();
        if (!(event instanceof ToolTraceRecord record)) {
            throw new ReplayMismatchException(
                    "期望工具事件，实际是 " + event.getClass().getSimpleName());
        }
        if (toolName != null && !toolName.equals(record.toolName())) {
            throw new ReplayMismatchException(
                    "工具名不一致: recorded=" + record.toolName() + ", actual=" + toolName);
        }
        return record;
    }

    public void verifyToolResult(ToolTraceRecord record, String actualResult) {
        String actualPreview = TextRedactor.redact(actualResult, 400);
        if (!actualPreview.equals(record.resultPreview())) {
            throw new ReplayMismatchException(
                    "工具结果摘要不一致: recorded=[" + record.resultPreview()
                            + "], actual=[" + actualPreview + "]");
        }
    }

    public void assertExhausted() {
        if (index != timeline.size()) {
            throw new ReplayMismatchException(
                    "回放 [" + runId + "] 还剩 " + remaining() + " 条事件未消费");
        }
    }
}
