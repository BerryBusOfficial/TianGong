package org.wutong.tiangong.core.storage.query.operator;

public enum Operator {

    EQ,         // =
    NE,         // <>
    GT,         // >
    GE,         // >=
    LT,         // <
    LE,         // <=

    LIKE,
    IN,
    NOT_IN,

    IS_NULL,
    IS_NOT_NULL
}