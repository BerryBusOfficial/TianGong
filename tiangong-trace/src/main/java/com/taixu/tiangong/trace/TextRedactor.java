package com.taixu.tiangong.trace;

import java.util.regex.Pattern;

/**
 * trace 脱敏：在落库/打日志前抹掉密钥、token、邮箱等敏感串，再截断。<br>
 * <br>
 * <b>边界</b>：规则匹配不是完备的 DLP。它降低「把 Bearer/sk-/github_pat_ 直接写进观测库」
 * 的概率，不保证识别所有业务敏感信息；真正敏感的数据不应进入 trace，而不是指望这里兜底。
 */
public final class TextRedactor {

    private static final Pattern[] PATTERNS = {
            Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}"),
            Pattern.compile("(?i)((?:api[_-]?key|token|secret|password)\\s*[:=]\\s*)[^\\s,;]+"),
            Pattern.compile("sk-[A-Za-z0-9_-]{8,}"),
            Pattern.compile("github_pat_[A-Za-z0-9_]{8,}"),
            Pattern.compile("ghp_[A-Za-z0-9]{8,}"),
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            Pattern.compile("(?i)(authorization\\s*:\\s*basic\\s+)[A-Za-z0-9+/=]{8,}"),
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    };

    private static final String[] REPLACEMENTS = {
            "$1***",
            "$1***",
            "sk-***",
            "github_pat_***",
            "ghp_***",
            "AKIA***",
            "$1***",
            "***@***"
    };

    private TextRedactor() {
    }

    public static String redact(String text) {
        return redact(text, 600);
    }

    public static String redact(String text, int maxChars) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String redacted = text;
        for (int i = 0; i < PATTERNS.length; i++) {
            redacted = PATTERNS[i].matcher(redacted).replaceAll(REPLACEMENTS[i]);
        }
        redacted = redacted.replaceAll("\\s+", " ").trim();
        int limit = Math.max(1, maxChars);
        return redacted.length() <= limit ? redacted : redacted.substring(0, limit) + "…";
    }
}
