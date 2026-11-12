package org.wutong.tiangong.common.exception;

public class ToolInvokeException extends SystemRuntimeException {

    public ToolInvokeException(String toolName) {
        super("[%s] 工具调用失败".formatted(toolName));
    }

    public ToolInvokeException(String toolName, Throwable cause) {
        super("[%s] 工具调用失败".formatted(toolName), cause);
    }

    public ToolInvokeException(Throwable cause) {
        super(cause);
    }
}
