package com.taixu.tiangong.common.exception;

/**
 * 资源不存在异常：@Resource 注入/加载时找不到指定资源。
 */
public class ResourceNotFoundException extends SystemRuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public ResourceNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
