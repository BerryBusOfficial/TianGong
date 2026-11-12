package com.taixu.tiangong.annotations.annotation.framework.exception;

/**
 * 异常 fallback 契约：{@code @ListenException} 指定的 fallback 类实现此接口，
 * 被监听方法抛出目标异常时收到通知。
 */
public interface ExceptionFallback {

    /**
     * 收到被监听方法抛出的目标异常（监听后异常仍会重新抛出给调用方）
     */
    void onException(Throwable error);
}
