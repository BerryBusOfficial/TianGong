package com.taixu.tiangong.guardrail;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 提示注入启发式检测。<br>
 * <br>
 * <b>不是分类器</b>：只覆盖常见「忽略之前指令 / 泄露系统提示 / 开发者模式」句式。
 * 命中时输入阶段直接拦截；工具/模型输出阶段做脱敏而不是整段丢弃（文档本身可能合法地
 * 讨论 prompt injection）。真正要防注入，还需要指令层级与不可信内容围栏配合。
 */
public final class PromptInjectionGuardrail implements Guardrail {

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("(?i)ignore\\s+(all\\s+)?(previous|prior|above)\\s+instructions"),
            Pattern.compile("(?i)disregard\\s+(all\\s+)?(previous|prior|above)\\s+instructions"),
            Pattern.compile("(?i)reveal\\s+(your\\s+)?system\\s+prompt"),
            Pattern.compile("(?i)you\\s+are\\s+now\\s+(a|an)\\s+"),
            Pattern.compile("忽略(之前|以上|前面|先前).{0,8}(指令|要求|提示)"),
            Pattern.compile("泄露.{0,8}(系统提示|提示词|prompt)"),
            Pattern.compile("开发者模式")
    );

    @Override
    public String name() {
        return "prompt-injection";
    }

    @Override
    public GuardrailResult inspect(GuardrailContext context) {
        String current = context.text();
        boolean hit = false;
        for (Pattern pattern : PATTERNS) {
            if (pattern.matcher(current).find()) {
                hit = true;
                if (context.stage() != GuardrailStage.INPUT) {
                    current = pattern.matcher(current).replaceAll("[filtered]");
                }
            }
        }
        if (!hit) {
            return GuardrailResult.allow(current);
        }
        if (context.stage() == GuardrailStage.INPUT) {
            return GuardrailResult.block("检测到疑似提示注入指令");
        }
        return GuardrailResult.redact(current, "疑似注入片段已替换");
    }
}
