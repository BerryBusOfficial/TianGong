package com.taixu.tiangong.annotations.processor.util;

/**
 * 生成代码时的命名工具。<br>
 * 放这里是因为两个处理器都要用同一套规则：源侧生成常量、调用侧引用常量，
 * 规则一旦两边不一致就会静默错位（编译能过，运行时查不到）。
 */
public final class NameUtils {

    private NameUtils() {
    }

    /**
     * camelCase → UPPER_SNAKE：{@code getSystemTime} → {@code GET_SYSTEM_TIME}。<br>
     * <br>
     * 已经带分隔符的名字原样保留，不会退化成 {@code SYS__TIME} 这种双下划线 ——
     * 工具名里混用 {@code sys_read} 和 {@code readUser} 两种风格是很常见的。
     */
    public static String toUpperSnake(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && !isSeparator(name.charAt(i - 1))) {
                    sb.append('_');
                }
                sb.append(c);
            } else if (isSeparator(c)) {
                sb.append('_');
            } else {
                sb.append(Character.toUpperCase(c));
            }
        }
        return sb.toString();
    }

    private static boolean isSeparator(char c) {
        return c == '_' || c == '-' || c == ' ';
    }
}
