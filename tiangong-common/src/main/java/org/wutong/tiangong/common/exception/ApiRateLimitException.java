package org.wutong.tiangong.common.exception;

/**
 * API 限流/配额不足（HTTP 429 / error_code=rate_limit_error|insufficient_quota）。
 */
public class ApiRateLimitException extends ApiRequestException {

    public ApiRateLimitException(String message, int statusCode, String errorCode) {
        super(message, statusCode, errorCode);
    }
}
