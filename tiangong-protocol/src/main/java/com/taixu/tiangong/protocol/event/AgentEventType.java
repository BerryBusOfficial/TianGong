package com.taixu.tiangong.protocol.event;

/**
 * 类型化 Agent 事件的协议类型。<br>
 * <br>
 * 枚举名是 SDK 里的稳定符号，{@link #wireName()} 是 SSE {@code event:} 与 JSON
 * {@code type} 字段上的线上值。二者刻意分开：线上改名不能把客户端强类型枚举一起改掉。
 */
public enum AgentEventType {

    MESSAGE_DELTA("message.delta"),
    REASONING_DELTA("reasoning.delta"),
    TOOL_CALL("tool.call"),
    TOOL_RESULT("tool.result"),
    CITATION("citation"),
    ARTIFACT("artifact"),
    USAGE("usage"),
    STRUCTURED("structured"),
    ERROR("error"),
    DONE("done");

    private final String wireName;

    AgentEventType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /**
     * 按线上名反查；未知事件返回 {@code null}，让客户端可以向前兼容（跳过不认识的事件），
     * 而不是因为服务端新增了一种事件就把整条流解析失败。
     */
    public static AgentEventType fromWireName(String wireName) {
        if (wireName == null || wireName.isBlank()) {
            return null;
        }
        for (AgentEventType type : values()) {
            if (type.wireName.equals(wireName)) {
                return type;
            }
        }
        return null;
    }
}
