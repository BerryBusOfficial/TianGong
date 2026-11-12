package com.taixu.tiangong.skill.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 在 {@code PATH} 里查找可执行文件。<br>
 * <br>
 * 用途：<b>探测工具链是否可用</b>（node / python 在不在）。脚本运行时必须能
 * 「诚实地说不」—— 环境没有就别接管对应后缀，否则模型会以为脚本已经跑过了。<br>
 * <br>
 * <h2>为什么要自己找</h2>
 * 直接 {@code new ProcessBuilder("npx", ...)} 也可以，失败再兜底；但那样
 * 每次执行都要先付出一次进程启动失败的代价，且错误信息不明确。这里一次性探测并
 * <b>缓存绝对路径</b>，顺带避开 Windows 上 {@code npx} 需要 {@code .cmd} 才能
 * 被 {@code ProcessBuilder} 直接调用的坑。
 */
public final class ExecutableLocator {

    private ExecutableLocator() {
    }

    /**
     * 在 PATH 中查找可执行文件。
     *
     * @param name 程序名，不含扩展名（如 {@code "node"} / {@code "python"}）
     * @return 绝对路径；找不到时为空
     */
    public static synchronized Optional<Path> find(String name) {
        List<Path> dirs = pathDirectories();
        for (String candidate : candidateNames(name)) {
            for (Path dir : dirs) {
                Path file = dir.resolve(candidate);
                if (Files.isRegularFile(file) && Files.isExecutable(file)) {
                    return Optional.of(file.toAbsolutePath());
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 候选文件名：Windows 上补 {@code PATHEXT}（.COM/.EXE/.BAT/.CMD），
     * POSIX 上就是原名。
     */
    private static List<String> candidateNames(String name) {
        List<String> names = new ArrayList<>();
        names.add(name);
        if (isWindows()) {
            String pathExt = System.getenv("PATHEXT");
            if (pathExt == null || pathExt.isBlank()) {
                names.add(name + ".exe");
                names.add(name + ".cmd");
                names.add(name + ".bat");
            } else {
                for (String ext : pathExt.split(";")) {
                    if (!ext.isBlank()) {
                        names.add(name + ext.toLowerCase());
                    }
                }
            }
        }
        return names;
    }

    private static List<Path> pathDirectories() {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return List.of();
        }
        List<Path> dirs = new ArrayList<>();
        for (String part : path.split(java.io.File.pathSeparator)) {
            if (part.isBlank()) {
                continue;
            }
            try {
                dirs.add(Path.of(part.trim()));
            } catch (Exception ignore) {
                // PATH 里可能含非法片段，跳过即可
            }
        }
        return dirs;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
