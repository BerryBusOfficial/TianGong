package org.wutong.tiangong.user.tool;

import com.sshtools.twoslices.Toast;
import com.sshtools.twoslices.ToastType;
import com.taixu.tiangong.annotations.annotation.framework.agent.AgentFunction;
import com.taixu.tiangong.annotations.annotation.framework.agent.AgentTool;
import com.taixu.tiangong.annotations.annotation.framework.agent.ToolNames;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 系统级工具：时间、通知、读文件。<br>
 * <br>
 * 工具名常量由 {@link ToolNames} 在编译期生成到 {@link SystemToolNames}，
 * 引用方写 {@code SystemToolNames.GET_SYSTEM_TIME} 而不是裸字符串。
 */
@AgentTool
@ToolNames
public class SystemTool {

    @AgentFunction(
            name = "get_sys_time",
            description = "获取系统当前时间"
    )
    public String getSystemTime() {
        return LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"));
    }

    @AgentFunction(
            name = "sys_notice",
            description = "弹出系统通知，弹出成功则返回true，否则返回false"
    )
    public boolean notice(
            @AgentFunction.ToolParam(name = "title", description = "通知标题") String title,
            @AgentFunction.ToolParam(name = "content", description = "通知内容") String content
    ) {
        Toast.toast(ToastType.INFO, title, content);
        return true;
    }

    @AgentFunction(
            name = "sys_read",
            description = "读取文件内容"
    )
    public String read(
            @AgentFunction.ToolParam(name = "path", description = "文件路径") String path,
            @AgentFunction.ToolParam(name = "offset", description = "起始行数", required = false, defaultValue = "0") int offset,
            @AgentFunction.ToolParam(name = "limit", description = "最大阅读行数", required = false, defaultValue = "-1") int limit
    ) {
        // 参数校验
        if (path == null || path.isBlank()) {
            return "错误：文件路径不能为空";
        }
        if (offset < 0) {
            return "错误：起始行数不能为负数";
        }

        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new FileReader(path, StandardCharsets.UTF_8))) {

            String line;
            int currentLine = 0;
            int readCount = 0;
            // 如果 limit <= 0，表示读取到文件末尾（即 Integer.MAX_VALUE）
            int maxLines = (limit <= 0) ? Integer.MAX_VALUE : limit;

            while ((line = reader.readLine()) != null) {
                if (currentLine >= offset) {
                    lines.add(line);
                    readCount++;
                    if (readCount >= maxLines) {
                        break;
                    }
                }
                currentLine++;
            }

            // 如果未读取到任何内容
            if (lines.isEmpty()) {
                return "文件从第 %d 行开始没有内容（文件总行数可能小于 %d）".formatted(offset, offset);
            }

            // 拼接结果（保留换行符）
            return String.join("\n", lines);

        } catch (IOException e) {
            return "读取文件失败：%s".formatted(e.getMessage());
        }
    }

}
