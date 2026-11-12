package com.taixu.tiangong.common.exception;

public class SystemRuntimeException extends RuntimeException {
    public SystemRuntimeException(String message) {
        super(message);
    }

    public SystemRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }

    public SystemRuntimeException(Throwable cause) {
        super(cause);
    }
}
