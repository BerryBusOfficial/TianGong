package com.taixu.tiangong.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 对象类型
 */
@Getter
@AllArgsConstructor
public enum ResourceType {

    REQUEST("request"),
    RESPONSE("response"),
    CHUNK("chunk"),
    AGENT_INSTANCE("instance"),
    SESSION("session"),
    STATE("state"),
    DECISION("decision"),
    ACTION("action"),
    TOOL_CALL("call"),
    EVENT("event"),
    CONTEXT("context"),
    UNIT("unit"),
    RELATION("relation"),
    COMMON("common"),
    NODE("node"),
    EDGE("edge"),
    VECTOR("vector"),
    ;

    private final String value;
}