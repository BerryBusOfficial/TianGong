package com.taixu.tiangong.protocol.event;

/**
 * 事件信封：把「第几条、属于哪次运行、什么时候发生」与业务事件分开。<br>
 * <br>
 * {@code seq} 是断点续传的核心：SSE 的 {@code id:} 与客户端的 {@code Last-Event-ID}
 * 都用它。要求<b>同一次运行内单调递增且不跳号</b>，否则客户端无法判断是否漏事件。
 *
 * @param seq       运行内序号，从 1 开始
 * @param runId     一次 Agent 运行 id（跨轮次稳定）
 * @param turnId    本轮对话 id（一次用户输入 = 一轮）
 * @param timestamp 事件产生时间（epoch millis）
 * @param event     业务事件
 */
public record AgentEventEnvelope(
        long seq,
        String runId,
        String turnId,
        long timestamp,
        AgentEvent event
) {

    public AgentEventEnvelope {
        if (seq <= 0) {
            throw new IllegalArgumentException("seq 必须从 1 开始: " + seq);
        }
        if (event == null) {
            throw new IllegalArgumentException("event 不能为空");
        }
    }

    public AgentEventType type() {
        return event.type();
    }

    public String eventName() {
        return event.type().wireName();
    }
}
