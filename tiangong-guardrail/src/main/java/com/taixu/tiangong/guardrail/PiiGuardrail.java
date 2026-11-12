package com.taixu.tiangong.guardrail;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PII / 密钥脱敏：邮箱、手机号、身份证、常见的 `sk-` / `github_pat_` 令牌。<br>
 * <br>
 * 规则脱敏不是完备 DLP，只覆盖最常见的泄露形态；真正敏感的数据不应进入模型/日志。
 */
public final class PiiGuardrail implements Guardrail {

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
                    "***@***"),
            new Rule(Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)"), "1**********"),
            new Rule(Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)"), "******************"),
            new Rule(Pattern.compile("sk-[A-Za-z0-9_-]{8,}"), "sk-***"),
            new Rule(Pattern.compile("github_pat_[A-Za-z0-9_]{8,}"), "github_pat_***"),
            new Rule(Pattern.compile("ghp_[A-Za-z0-9]{8,}"), "ghp_***")
    );

    @Override
    public String name() {
        return "pii";
    }

    @Override
    public GuardrailResult inspect(GuardrailContext context) {
        String current = context.text();
        List<String> hits = new ArrayList<>();
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern().matcher(current);
            if (matcher.find()) {
                current = matcher.replaceAll(rule.replacement());
                hits.add(rule.pattern().pattern());
            }
        }
        if (hits.isEmpty()) {
            return GuardrailResult.allow(current);
        }
        return GuardrailResult.redact(current, "PII/密钥已脱敏");
    }

    private record Rule(Pattern pattern, String replacement) {
    }
}
