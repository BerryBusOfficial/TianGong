package org.wutong.tiangong.common.exception;

public class HttpTransportException extends RuntimeException {
    public HttpTransportException(String message) {
        super(message);
    }

    public HttpTransportException(String message, Throwable cause) {
        super(message, cause);
    }

    public HttpTransportException(Throwable cause) {
        super(cause);
    }
}
