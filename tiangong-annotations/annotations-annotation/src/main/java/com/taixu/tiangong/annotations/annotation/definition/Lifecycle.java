package com.taixu.tiangong.annotations.annotation.definition;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Bean 生命周期策略。
 */
@Getter
@AllArgsConstructor
public enum Lifecycle {

    /**
     * Agent 全局唯一实例
     */
    SINGLETON("singleton"),
    /**
     * 每次获取时创建新实例
     */
    PROTOTYPE("prototype"),
    ;

    @JsonValue
    private final String name;


}
