package org.wutong.tiangong.user.util;

public class ConsoleColor {
    // 重置
    public static final String RESET = "\u001B[0m";
    // 前景色 (文字颜色)
    public static final String BLACK = "\u001B[30m";
    public static final String RED = "\u001B[31m";
    public static final String GREEN = "\u001B[32m";
    public static final String YELLOW = "\u001B[33m";
    public static final String BLUE = "\u001B[34m";
    public static final String PURPLE = "\u001B[35m";
    public static final String CYAN = "\u001B[36m";
    public static final String WHITE = "\u001B[37m";

    // 背景色
    public static final String BLACK_BACKGROUND = "\u001B[40m";
    public static final String RED_BACKGROUND = "\u001B[41m";

    public static String loadColor(String foregroundColor, String backgroundColor, String content) {
        return "%s%s%s%s".formatted(foregroundColor, backgroundColor, content, ConsoleColor.RESET);
    }

    public static String loadColor(String color, String content) {
        return "%s%s%s".formatted(color, content, ConsoleColor.RESET);
    }

}