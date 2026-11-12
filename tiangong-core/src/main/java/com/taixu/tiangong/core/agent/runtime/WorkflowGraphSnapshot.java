package com.taixu.tiangong.core.agent.runtime;

import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import com.taixu.tiangong.core.agent.AgentNodeDefinition;
import com.taixu.tiangong.core.agent.AgentNodeRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 工作流图快照：把节点/边/起始条件压成稳定的文本，供 golden 测试比对。<br>
 * <br>
 * <b>为什么不是拍图</b>：图可视化会受布局库版本影响，CI 里不稳定；这里只快照结构事实
 * （节点名、类型、next、start 条件、replay 策略），节点顺序按名字排序，保证跨机器一致。
 * 图渲染属于文档产物，不进入回归门禁。
 */
public final class WorkflowGraphSnapshot {

    private static final String HEADER = "# tiangong-workflow-graph v1";

    private WorkflowGraphSnapshot() {
    }

    public static String snapshot(AgentNodeRegistry registry) {
        if (registry == null) {
            throw new IllegalArgumentException("AgentNodeRegistry 不能为空");
        }
        StringBuilder text = new StringBuilder(HEADER).append('\n');
        List<AgentNodeDefinition> nodes = new ArrayList<>(registry.values());
        nodes.sort(Comparator.comparing(AgentNodeDefinition::name));
        for (AgentNodeDefinition node : nodes) {
            String next = node.next() == null ? "" : String.join(",", node.next());
            text.append("node ").append(node.name()).append('|')
                    .append(node.type()).append('|')
                    .append(node.startConditionClass() == null
                            ? "-" : node.startConditionClass().getSimpleName()).append('|')
                    .append(node.replayPolicy()).append('|')
                    .append(next).append('\n');
        }
        List<String> starts = registry.startNodes().stream()
                .map(AgentNodeDefinition::name)
                .sorted()
                .toList();
        text.append("start ").append(String.join(",", starts)).append('\n');
        text.append("end ").append(registry.byType(NodeType.END).size()).append('\n');
        return text.toString();
    }

    public static void write(AgentNodeRegistry registry, Path path) throws IOException {
        Files.writeString(path, snapshot(registry), StandardCharsets.UTF_8);
    }

    /**
     * 与 golden 文件比对；不一致时抛出带 expected/actual 的 AssertionError。
     */
    public static void assertMatches(AgentNodeRegistry registry, Path golden) throws IOException {
        String expected = Files.readString(golden, StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        String actual = snapshot(registry);
        if (!expected.equals(actual)) {
            throw new AssertionError("工作流图快照不一致:\n--- expected ---\n"
                    + expected + "--- actual ---\n" + actual);
        }
    }
}
