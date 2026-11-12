package org.wutong.tiangong.llm.prompt;


import org.wutong.tiangong.common.exception.SystemRuntimeException;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Prompt构建器
 * <p>
 * 支持:
 * <p>
 * {key}
 * {memory.short}
 * {user.name}
 *
 */
public final class PromptBuilder {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{([a-zA-Z0-9_.-]+)}");

    private final PromptTemplate template;
    private final Map<String, Object> variables = new HashMap<>();
    private final Map<Class<?>, Function<Object, String>> formatters = new HashMap<>();


    /**
     * 严格模式
     * <p>
     * 缺少变量直接失败
     */
    private boolean strict = true;

    private PromptBuilder(PromptTemplate template) {
        this.template = template;
    }

    public static PromptBuilder builder(PromptTemplate template) {
        return new PromptBuilder(template);
    }

    public PromptBuilder set(String key, Object value) {
        variables.put(key, value);
        return this;
    }


    public PromptBuilder setAll(Map<String, Object> values) {
        if (values != null) {
            variables.putAll(values);
        }
        return this;
    }

    /**
     * 注册对象格式化器
     * <p>
     * 例如:
     * <p>
     * FactNode -> Prompt文本
     */
    public <T> PromptBuilder formatter(Class<T> type, Function<T, String> formatter) {
        formatters.put(type, obj -> formatter.apply(type.cast(obj)));
        return this;
    }

    public PromptBuilder strict(boolean enable) {
        this.strict = enable;
        return this;
    }


    public String build() {
        String text = template.getTemplate();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder();
        Set<String> used = new HashSet<>();
        while (matcher.find()) {
            String key = matcher.group(1);
            used.add(key);
            Object value = resolve(key);
            if (value == null && strict) {
                throw new SystemRuntimeException("Missing prompt variable: {%s}".formatted(key));
            }

            String replacement = value == null ? "" : format(value);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        checkUnusedVariables(used);
        return result.toString();
    }


    private Object resolve(String key) {
        return variables.get(key);
    }


    private String format(Object value) {
        Function<Object, String> formatter = formatters.get(value.getClass());

        if (formatter != null) {
            return formatter.apply(value);
        }

        if (value instanceof Collection<?> collection) {
            StringBuilder sb = new StringBuilder();
            for (Object obj : collection) {
                sb.append(format(obj)).append("\n");
            }
            return sb.toString();
        }
        return String.valueOf(value);
    }

    private void checkUnusedVariables(Set<String> used) {
        if (!strict) {
            return;
        }

        for (String key : variables.keySet()) {
            if (!used.contains(key)) {
                System.err.printf("Unused prompt variable: %s%n", key);
            }
        }

    }

}