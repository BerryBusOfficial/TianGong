package org.wutong.tiangong.common.exception;

public class NodeNameConflictException extends SystemRuntimeException {

    public NodeNameConflictException(Throwable cause) {
        super(cause);
    }

    public NodeNameConflictException(String message, Throwable cause) {
        super(message, cause);
    }

    public NodeNameConflictException(String message) {
        super(message);
    }
}
