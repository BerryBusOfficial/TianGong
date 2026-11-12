package com.taixu.tiangong.guardrail;

/**
 * 护栏作用阶段。不同阶段对同一内容的处理策略可以不同：
 * 输入注入应拦截，工具输出里的可疑文本更适合脱敏/围栏而不是整段丢弃。
 */
public enum GuardrailStage {
    INPUT,
    TOOL_OUTPUT,
    MODEL_OUTPUT
}
