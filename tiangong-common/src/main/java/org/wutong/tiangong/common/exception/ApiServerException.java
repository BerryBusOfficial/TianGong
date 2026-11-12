package org.wutong.tiangong.common.exception;

/**
 * API 服务端错误（HTTP 5xx / error_code=server_error）。
 */
public class ApiServerException extends ApiRequestException {

    public ApiServerException(String message, int statusCode, String errorCode) {
        super(message, statusCode, errorCode);
    }
}
