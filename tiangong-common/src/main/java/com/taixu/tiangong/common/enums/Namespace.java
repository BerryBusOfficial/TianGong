package com.taixu.tiangong.common.enums;


import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 系统域
 */
@Getter
@AllArgsConstructor
public enum Namespace {
    /**
     * Agent核心
     */
    AGENT("agent"),
    /**
     * 会话
     */
    SESSION("session"),
    /**
     * LLM调用
     */
    LLM("llm"),
    /**
     * 工具系统
     */
    TOOL("tool"),
    /**
     * 记忆系统
     */
    MEMORY("memory"),
    /**
     * 事件
     */
    EVENT("event"),
    /**
     * 状态
     */
    STATE("state"),
    /**
     * 框架
     */
    FRAMEWORK("framework"),
    /**
     * 跟踪
     */
    TRACE("trace"),
    ;

    private final String value;
}