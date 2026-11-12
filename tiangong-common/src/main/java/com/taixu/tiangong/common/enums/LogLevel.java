package com.taixu.tiangong.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum LogLevel {

    TRACE(0, "TRACE"),
    DEBUG(10, "DEBUG"),
    INFO(20, "INFO"),
    WARN(30, "WARN"),
    ERROR(40, "ERROR"),
    FATAL(50, "FATAL"),
    ;

    private final int level;
    private final String name;

}
