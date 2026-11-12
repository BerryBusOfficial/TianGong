package com.taixu.tiangong.common.exception;

public class UnsupportedInvocationException extends RuntimeException {
    public UnsupportedInvocationException() {
        super("Unsupported invocation");
    }

    public UnsupportedInvocationException(String message) {
        super(message);
    }
}
