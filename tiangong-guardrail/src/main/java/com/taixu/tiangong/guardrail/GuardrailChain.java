package com.taixu.tiangong.guardrail;

import java.util.ArrayList;
import java.util.List;

/**
 * 有序护栏链：前一个的改写结果作为后一个的输入；遇到 BLOCK 立即停止。<br>
 * <br>
 * <b>失败默认拦截</b>：某个护栏抛异常时不再放行剩余内容——护栏本身坏掉时，
 * 最安全的语义是拒绝，而不是把未检查的内容送进模型。
 */
public final class GuardrailChain {

    private final List<Guardrail> guardrails;

    private GuardrailChain(List<Guardrail> guardrails) {
        this.guardrails = List.copyOf(guardrails);
    }

    public static GuardrailChain empty() {
        return new GuardrailChain(List.of());
    }

    public static GuardrailChain of(Guardrail... guardrails) {
        return new GuardrailChain(List.of(guardrails));
    }

    public static Builder builder() {
        return new Builder();
    }

    public GuardrailResult inspect(GuardrailContext context) {
        String current = context.text();
        List<String> reasons = new ArrayList<>();
        for (Guardrail guardrail : guardrails) {
            GuardrailResult result;
            try {
                result = guardrail.inspect(context.withText(current));
            } catch (RuntimeException e) {
                return GuardrailResult.block(
                        "护栏 [" + guardrail.name() + "] 执行异常（按拦截处理）: " + e.getMessage());
            }
            if (result == null) {
                continue;
            }
            if (result.decision() == GuardrailDecision.BLOCK) {
                return result;
            }
            if (result.decision() == GuardrailDecision.REDACT) {
                current = result.text();
                if (result.reason() != null) {
                    reasons.add(result.reason());
                }
            }
        }
        return reasons.isEmpty()
                ? GuardrailResult.allow(current)
                : GuardrailResult.redact(current, String.join("；", reasons));
    }

    public List<Guardrail> guardrails() {
        return guardrails;
    }

    public static final class Builder {

        private final List<Guardrail> guards = new ArrayList<>();

        public Builder add(Guardrail guardrail) {
            if (guardrail != null) {
                guards.add(guardrail);
            }
            return this;
        }

        public GuardrailChain build() {
            return new GuardrailChain(guards);
        }
    }
}
