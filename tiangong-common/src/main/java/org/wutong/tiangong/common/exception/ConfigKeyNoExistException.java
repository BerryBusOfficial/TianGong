package org.wutong.tiangong.common.exception;

public class ConfigKeyNoExistException extends SystemRuntimeException {

    public ConfigKeyNoExistException(String message) {
        super(message);
    }

    public ConfigKeyNoExistException(String message, Throwable cause) {
        super(message, cause);
    }

    public ConfigKeyNoExistException(Throwable cause) {
        super(cause);
    }
}
