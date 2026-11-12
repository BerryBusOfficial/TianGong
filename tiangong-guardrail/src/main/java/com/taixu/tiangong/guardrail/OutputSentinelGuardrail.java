package com.taixu.tiangong.guardrail;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 输出哨兵清理：模型偶尔会把内部协议标记（{@code <<<...>>>} / {@code <|...|>}）吐进正文。
 * 这些标记不是给用户看的，也不应被下游当成结构指令；MODEL_OUTPUT 阶段替换掉。
 */
public final class OutputSentinelGuardrail implements Guardrail {

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("<<<[A-Z0-9_:-]{1,64}>>>"),
            Pattern.compile("<\\|[^|\\n]{1,64}\\|>"),
            Pattern.compile("\\[\\[[A-Z0-9_:-]{1,64}]]")
    );

    @Override
    public String name() {
        return "output-sentinel";
    }

    @Override
    public GuardrailResult inspect(GuardrailContext context) {
        if (context.stage() != GuardrailStage.MODEL_OUTPUT) {
            return GuardrailResult.allow(context.text());
        }
        String current = context.text();
        boolean hit = false;
        for (Pattern pattern : PATTERNS) {
            if (pattern.matcher(current).find()) {
                current = pattern.matcher(current).replaceAll("[sentinel-filtered]");
                hit = true;
            }
        }
        return hit
                ? GuardrailResult.redact(current, "输出中的内部哨兵已清理")
                : GuardrailResult.allow(current);
    }
}
