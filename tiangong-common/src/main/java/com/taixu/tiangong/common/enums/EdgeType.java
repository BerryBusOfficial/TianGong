package com.taixu.tiangong.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum EdgeType {

    SEMANTIC("semantic"),
    RELATIONAL("relational");

    private final String value;
}
