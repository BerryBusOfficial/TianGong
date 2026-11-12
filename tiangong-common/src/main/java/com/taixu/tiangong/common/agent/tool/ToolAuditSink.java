package com.taixu.tiangong.common.agent.tool;

/**
 * 工具审计出口。<br>
 * 默认空实现（不记录）；需要审计的部署注册自己的实现（DB/日志/消息队列）。<br>
 * <b>不要在实现里阻塞工具执行</b>：审计是旁路，写失败不应让业务工具失败。
 */
@FunctionalInterface
public interface ToolAuditSink {

    void record(ToolAuditEvent event);

    /** 默认空实现，便于可选注入 */
    ToolAuditSink NOOP = event -> {
    };
}
