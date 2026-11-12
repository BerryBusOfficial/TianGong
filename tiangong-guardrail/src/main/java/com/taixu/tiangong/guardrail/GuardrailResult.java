package com.taixu.tiangong.guardrail;

/**
 * 护栏输出：允许、改写（脱敏/围栏）或拦截。
 *
 * @param text   改写后的文本；BLOCK 时可为原文本或空串
 * @param reason 改写/拦截原因，用于日志与审计
 */
public record GuardrailResult(GuardrailDecision decision, String text, String reason) {

    public static GuardrailResult allow(String text) {
        return new GuardrailResult(GuardrailDecision.ALLOW, text == null ? "" : text, null);
    }

    public static GuardrailResult redact(String text, String reason) {
        return new GuardrailResult(GuardrailDecision.REDACT, text == null ? "" : text, reason);
    }

    public static GuardrailResult block(String reason) {
        return new GuardrailResult(GuardrailDecision.BLOCK, "", reason);
    }

    public boolean blocked() {
        return decision == GuardrailDecision.BLOCK;
    }
}
