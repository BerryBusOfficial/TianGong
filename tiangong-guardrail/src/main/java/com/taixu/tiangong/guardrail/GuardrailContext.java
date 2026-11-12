package com.taixu.tiangong.guardrail;

import com.taixu.tiangong.common.agent.call.AgentCallContext;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次护栏检查的输入。
 *
 * @param text        待检查文本
 * @param callContext 调用者身份（租户链路由它决定）
 * @param metadata    阶段相关元数据（如工具名、schema 名）
 */
public record GuardrailContext(
        GuardrailStage stage,
        String text,
        AgentCallContext callContext,
        Map<String, Object> metadata
) {

    public GuardrailContext {
        if (stage == null) {
            throw new IllegalArgumentException("stage 不能为空");
        }
        text = text == null ? "" : text;
        metadata = metadata == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public static GuardrailContext of(GuardrailStage stage, String text, AgentCallContext context) {
        return new GuardrailContext(stage, text, context, Map.of());
    }

    public GuardrailContext withText(String nextText) {
        return new GuardrailContext(stage, nextText, callContext, metadata);
    }
}
