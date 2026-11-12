package org.wutong.tiangong.spring.test;

/** 测试用业务异常：验证桥的"类型保真"（不被包装成别的异常） */
public class BridgeBizException extends RuntimeException {

    public BridgeBizException(String message) {
        super(message);
    }
}
