package com.taixu.tiangong.trace;

import java.util.List;
import java.util.Optional;

/**
 * trace 存储。内存实现用于单机/测试；生产可落 DB/日志平台。<br>
 * 查询接口按运行维度组织，避免调用方自己遍历所有事件。
 */
public interface TraceStore {

    void record(LlmTraceRecord record);

    void record(ToolTraceRecord record);

    List<TraceEvent> timeline(String runId);

    default List<LlmTraceRecord> llmCalls(String runId) {
        return timeline(runId).stream()
                .filter(LlmTraceRecord.class::isInstance)
                .map(LlmTraceRecord.class::cast)
                .toList();
    }

    default List<ToolTraceRecord> toolCalls(String runId) {
        return timeline(runId).stream()
                .filter(ToolTraceRecord.class::isInstance)
                .map(ToolTraceRecord.class::cast)
                .toList();
    }

    /** 按 prompt hash 找记录；用于 golden trace 回放/结果比对 */
    default Optional<LlmTraceRecord> findByPromptHash(String runId, String promptHash) {
        return llmCalls(runId).stream()
                .filter(record -> promptHash != null && promptHash.equals(record.promptHash()))
                .findFirst();
    }
}
