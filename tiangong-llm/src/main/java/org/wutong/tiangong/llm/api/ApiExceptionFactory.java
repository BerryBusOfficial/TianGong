package org.wutong.tiangong.llm.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.wutong.tiangong.common.exception.*;
import org.wutong.tiangong.common.util.JsonUtil;

/**
 * API 错误工厂：按 HTTP 状态码 + 错误体（error.code）映射为对应的异常类。<br>
 * 支持 OpenAI 风格错误体（{"error":{"message","type","code"}}）与 Ollama 风格（{"error":"..."}）。
 */
public final class ApiExceptionFactory {

    private ApiExceptionFactory() {
    }

    public static ApiRequestException from(int statusCode, String body) {
        String message = body;
        String errorCode = null;
        try {
            JsonNode root = JsonUtil.fromJson(body, JsonNode.class);
            JsonNode error = root.get("error");
            if (error != null) {
                if (error.isObject()) {
                    if (error.has("message")) {
                        message = error.get("message").asText();
                    }
                    if (error.has("code") && error.get("code").isTextual()) {
                        errorCode = error.get("code").asText();
                    }
                } else if (error.isTextual()) {
                    message = error.asText();
                }
            }
        } catch (Exception ignored) {
            // 错误体不是 JSON，保留原始 body 作为 message
        }
        return map(statusCode, errorCode, message, body);
    }

    private static ApiRequestException map(int statusCode, String errorCode, String message, String body) {
        String fullMessage = "API 请求失败：%s -> %s".formatted(statusCode, message);
        switch (statusCode) {
            case 401 -> {
                return new ApiAuthenticationException(fullMessage, statusCode, errorCode);
            }
            case 403 -> {
                return new ApiAuthenticationException(fullMessage, statusCode, errorCode);
            }
            case 429 -> {
                return new ApiRateLimitException(fullMessage, statusCode, errorCode);
            }
            default -> {
            }
        }
        if (errorCode != null) {
            if (errorCode.contains("rate_limit") || errorCode.contains("quota")) {
                return new ApiRateLimitException(fullMessage, statusCode, errorCode);
            }
            if (errorCode.contains("authentication") || errorCode.contains("permission")) {
                return new ApiAuthenticationException(fullMessage, statusCode, errorCode);
            }
            if (errorCode.contains("invalid_request")) {
                return new ApiInvalidRequestException(fullMessage, statusCode, errorCode);
            }
            if (errorCode.contains("server")) {
                return new ApiServerException(fullMessage, statusCode, errorCode);
            }
        }
        if (statusCode >= 500) {
            return new ApiServerException(fullMessage, statusCode, errorCode);
        }
        if (statusCode == 400) {
            return new ApiInvalidRequestException(fullMessage, statusCode, errorCode);
        }
        return new ApiRequestException(fullMessage, statusCode, errorCode);
    }
}
