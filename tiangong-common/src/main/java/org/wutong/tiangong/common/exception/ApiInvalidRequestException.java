package org.wutong.tiangong.common.exception;

/**
 * API 请求参数无效（HTTP 400 / error_code=invalid_request_error）。
 */
public class ApiInvalidRequestException extends ApiRequestException {

    public ApiInvalidRequestException(String message, int statusCode, String errorCode) {
        super(message, statusCode, errorCode);
    }
}
