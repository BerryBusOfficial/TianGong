package com.taixu.tiangong.trace;

import java.time.Instant;

/**
 * 可回放的 trace 事件。只保留「定位问题需要的最小事实」：谁、哪个节点、什么输入摘要、
 * 什么输出摘要、花了多少 token/成本、成功还是失败。<br>
 * <br>
 * <b>不记录完整 prompt/正文</b>：原文可能含员工数据/密钥，落库即扩大泄露面；用
 * {@link TextRedactor} 脱敏 + 截断，配合 {@link PromptHasher} 做精确比对。
 */
public sealed interface TraceEvent permits LlmTraceRecord, ToolTraceRecord {

    String runId();

    String turnId();

    String node();

    Instant timestamp();
}
