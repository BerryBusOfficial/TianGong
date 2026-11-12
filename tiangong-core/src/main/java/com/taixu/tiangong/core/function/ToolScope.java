package com.taixu.tiangong.core.function;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具级白/黑名单：按工具名（短名或 {@code BeanName.方法名}）做进程级裁剪。<br>
 * <br>
 * 与调用者 scope 的关系：本类是<b>部署策略</b>（这个进程/这个产品线只允许哪些工具），
 * {@code AgentCallContext.scopes} 是<b>调用者权限</b>（这个用户能做什么）。两者都要过：
 * 先过部署策略，再过调用者权限。<br>
 * <br>
 * 语义：allow 为空 = 不额外限制；deny 优先于 allow。通配只支持尾部 {@code *}
 * （{@code report:*} 匹配 {@code report:} 前缀），不做正则 —— 避免把安全规则写成
 * 难以审计的表达式。
 */
public final class ToolScope {

    private final List<String> allow;
    private final List<String> deny;

    private ToolScope(List<String> allow, List<String> deny) {
        this.allow = List.copyOf(allow);
        this.deny = List.copyOf(deny);
    }

    public static ToolScope unrestricted() {
        return new ToolScope(List.of(), List.of());
    }

    public static ToolScope allow(String... patterns) {
        List<String> list = new ArrayList<>();
        addPatterns(list, patterns);
        return new ToolScope(list, List.of());
    }

    /**
     * 只设置 deny（不设置 allow 白名单）。<br>
     * ⚠ 不要写成 {@code ToolScope.allow(...)} 后再调静态 {@code deny}：那会丢掉 allow。
     * 链式请用实例方法 {@link #deny(String...)}。
     */
    public static ToolScope denyOnly(String... patterns) {
        List<String> list = new ArrayList<>();
        addPatterns(list, patterns);
        return new ToolScope(List.of(), list);
    }

    /** 链式：在已有 allow 基础上追加 deny，语义等同 {@code allow("report:*").deny("admin:*")} */
    public ToolScope deny(String... patterns) {
        return andDeny(patterns);
    }

    public ToolScope andAllow(String... patterns) {
        List<String> list = new ArrayList<>(allow);
        addPatterns(list, patterns);
        return new ToolScope(list, deny);
    }

    public ToolScope andDeny(String... patterns) {
        List<String> list = new ArrayList<>(deny);
        addPatterns(list, patterns);
        return new ToolScope(allow, list);
    }

    private static void addPatterns(List<String> target, String[] patterns) {
        if (patterns == null) {
            return;
        }
        for (String pattern : patterns) {
            if (pattern != null && !pattern.isBlank()) {
                target.add(pattern.trim());
            }
        }
    }

    /** 是否被 deny 明确命中（deny 优先，调用方先问它） */
    public boolean denies(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        for (String pattern : deny) {
            if (matches(pattern, toolName)) {
                return true;
            }
        }
        return false;
    }

    /** 工具名是否被允许；allow 为空视为「不限制」，deny 永远优先 */
    public boolean permits(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        for (String pattern : deny) {
            if (matches(pattern, toolName)) {
                return false;
            }
        }
        if (allow.isEmpty()) {
            return true;
        }
        for (String pattern : allow) {
            if (matches(pattern, toolName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String pattern, String toolName) {
        if ("*".equals(pattern) || pattern.equals(toolName)) {
            return true;
        }
        if (pattern.endsWith("*")) {
            return toolName.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return false;
    }

    public List<String> allowPatterns() {
        return allow;
    }

    public List<String> denyPatterns() {
        return deny;
    }
}
