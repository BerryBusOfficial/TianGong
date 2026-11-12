package org.wutong.tiangong.common.exception;

import lombok.Getter;

/**
 * LLM API 请求异常：携带 HTTP 状态码与 provider 错误码。<br>
 * 按错误码/状态码细分：{@link ApiAuthenticationException} / {@link ApiRateLimitException} /
 * {@link ApiInvalidRequestException} / {@link ApiServerException}。
 */
@Getter
public class ApiRequestException extends RuntimeException {

    private final int statusCode;
    private final String errorCode;

    public ApiRequestException(String message) {
        this(message, 0, null, null);
    }

    public ApiRequestException(String message, Throwable cause) {
        this(message, 0, null, cause);
    }

    public ApiRequestException(Throwable cause) {
        this(cause == null ? "API 请求失败" : cause.getMessage(), 0, null, cause);
    }

    public ApiRequestException() {
        this("API 请求失败", 0, null, null);
    }

    public ApiRequestException(String message, int statusCode, String errorCode) {
        this(message, statusCode, errorCode, null);
    }

    public ApiRequestException(String message, int statusCode, String errorCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }
}
