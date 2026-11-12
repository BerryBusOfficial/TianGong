package com.taixu.tiangong.common.exception;

/**
 * 别名冲突异常：Alias 成对属性同时设置了不同的值。
 */
public class AliasException extends RuntimeException {

    public AliasException(String message) {
        super(message);
    }

    public AliasException(String message, Throwable cause) {
        super(message, cause);
    }
}
