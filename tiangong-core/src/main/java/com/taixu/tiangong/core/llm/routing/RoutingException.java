package com.taixu.tiangong.core.llm.routing;

/**
 * 路由/模型执行失败。携带每次尝试的摘要，避免只报最后一条异常而看不到降级链。
 */
public class RoutingException extends RuntimeException {

    public RoutingException(String message) {
        super(message);
    }

    public RoutingException(String message, Throwable cause) {
        super(message, cause);
    }
}
