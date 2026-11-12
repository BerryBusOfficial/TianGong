package com.taixu.tiangong.core.storage.query.operator;

public enum Direction {
    ASC,
    DESC;

    public boolean isDescending() {
        return this == DESC;
    }
}
