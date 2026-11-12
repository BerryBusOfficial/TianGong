package org.wutong.tiangong.common.exception;

/**
 * API 认证失败（HTTP 401 / error_code=authentication_error）：API Key 无效或缺失。
 */
public class ApiAuthenticationException extends ApiRequestException {

    public ApiAuthenticationException(String message, int statusCode, String errorCode) {
        super(message, statusCode, errorCode);
    }
}
