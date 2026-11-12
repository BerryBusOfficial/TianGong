package com.taixu.tiangong.protocol.event;

/**
 * 事件接收端：可以是内存日志（测试）、SSE 输出、指标收集或审计旁路。<br>
 * <br>
 * 只接收业务事件，序号/运行 id 由 {@link AgentEventJournal} 这类实现补全 ——
 * 让产生事件的代码不必关心"这是第几条"。
 */
@FunctionalInterface
public interface AgentEventSink {

    void emit(AgentEvent event);
}
