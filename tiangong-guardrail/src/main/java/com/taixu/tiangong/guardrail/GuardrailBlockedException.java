package com.taixu.tiangong.guardrail;

/**
 * 内容被护栏拦截。调用方应把它当作策略拒绝，而不是可重试的业务异常。
 */
public class GuardrailBlockedException extends RuntimeException {

    private final GuardrailStage stage;
    private final String reason;

    public GuardrailBlockedException(GuardrailStage stage, String reason) {
        super("护栏拦截（" + stage + "）: " + reason);
        this.stage = stage;
        this.reason = reason;
    }

    public GuardrailStage stage() {
        return stage;
    }

    public String reason() {
        return reason;
    }
}
