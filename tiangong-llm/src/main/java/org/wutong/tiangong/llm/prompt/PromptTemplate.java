package org.wutong.tiangong.llm.prompt;

import lombok.Getter;
import org.wutong.tiangong.common.exception.SystemRuntimeException;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Prompt模板
 * <p>
 * 负责解析模板中的变量占位符
 * <p>
 * 示例:
 * <p>
 * 你好 {user.name}
 * <p>
 * 记忆:
 * {memory.short}
 *
 */
public final class PromptTemplate {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{([a-zA-Z0-9_.-]+)}");

    @Getter
    private final String template;
    private final Set<String> variables;

    private PromptTemplate(String template) {
        this.template = template;
        this.variables = parseVariables(template);
    }


    public static PromptTemplate of(String template) {
        if (template == null || template.isBlank()) {
            throw new SystemRuntimeException("Prompt模板不能为空");
        }
        return new PromptTemplate(template);
    }


    public PromptBuilder builder() {
        return PromptBuilder.builder(this);
    }


    public Set<String> getVariables() {
        return Collections.unmodifiableSet(variables);
    }


    private Set<String> parseVariables(String text) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
    }


}