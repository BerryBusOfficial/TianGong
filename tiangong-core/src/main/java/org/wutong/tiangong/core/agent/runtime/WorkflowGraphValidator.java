package org.wutong.tiangong.core.agent.runtime;

import lombok.extern.slf4j.Slf4j;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import org.wutong.tiangong.common.exception.SystemRuntimeException;
import org.wutong.tiangong.core.agent.AgentNodeDefinition;
import org.wutong.tiangong.core.agent.AgentNodeRegistry;

import java.util.HashSet;
import java.util.Set;

/**
 * 工作流图合法性校验器：容器启动阶段检查所有节点的结构约束。<br>
 * <br>
 * 校验规则（启动期，不被异常注解捕获）：
 * <ol>
 *     <li>至少一个 START 节点</li>
 *     <li>每个 START 必须可达至少一个 END（可达性遍历）</li>
 *     <li>BRANCH 节点的 @NodeNext 必须 ≥ 2</li>
 *     <li>PARALLEL 节点的 @NodeNext 必须 ≥ 2</li>
 *     <li>END 节点不允许 @NodeNext</li>
 *     <li>START/COMMON/ENTRANCE/EXPORT/MERGE/COMMAND 节点的 @NodeNext 仅允许 0 或 1 个</li>
 *     <li>所有 @NodeNext 指向的节点必须存在</li>
 * </ol>
 * <br>
 * 校验失败抛 {@link SystemRuntimeException}（框架异常，不接受注解捕获）。<br>
 * <br>
 * <b>新增 {@link NodeType} 时务必同步本类的 switch</b>：这里是 switch <b>语句</b>而非
 * 表达式，没有 {@code default} 分支，因此未列出的类型会<b>静默跳过全部校验</b>
 * —— 编译能通过，约束却失效了。编译器不会提醒。
 */
@Slf4j
public class WorkflowGraphValidator {

    /**
     * 校验注册表中所有节点的合法性。
     */
    public static void validate(AgentNodeRegistry registry) {
        validateAtLeastOneStart(registry);
        validateNextReferences(registry);
        validateNodeTypeConstraints(registry);
        validateReachabilityToEnd(registry);
        log.info("工作流图校验通过：{} 个节点", registry.size());
    }

    /** 规则 1：至少一个 START */
    private static void validateAtLeastOneStart(AgentNodeRegistry registry) {
        if (registry.startNodes().isEmpty()) {
            throw new SystemRuntimeException("工作流图校验失败：未注册任何 START 节点");
        }
    }

    /** 规则 7：@NodeNext 指向的节点必须存在 */
    private static void validateNextReferences(AgentNodeRegistry registry) {
        for (AgentNodeDefinition node : registry.values()) {
            for (String nextName : node.next()) {
                if (!registry.contains(nextName)) {
                    throw new SystemRuntimeException(
                            "工作流图校验失败：节点 [" + node.name()
                                    + "] 的 @NodeNext 指向不存在的节点: [" + nextName + "]");
                }
            }
        }
    }

    /** 规则 2-6：各节点类型的 @NodeNext 数量约束 */
    private static void validateNodeTypeConstraints(AgentNodeRegistry registry) {
        for (AgentNodeDefinition node : registry.values()) {
            NodeType type = node.type();
            int nextCount = node.next() == null ? 0 : node.next().length;

            switch (type) {
                case END -> {
                    if (nextCount > 0) {
                        throw new SystemRuntimeException(
                                "工作流图校验失败：END 节点 [" + node.name()
                                        + "] 不允许 @NodeNext，当前有 " + nextCount + " 个");
                    }
                }
                case BRANCH -> {
                    if (nextCount < 2) {
                        throw new SystemRuntimeException(
                                "工作流图校验失败：BRANCH 节点 [" + node.name()
                                        + "] 的 @NodeNext 必须 ≥ 2，当前 " + nextCount + " 个");
                    }
                }
                case PARALLEL -> {
                    if (nextCount < 2) {
                        throw new SystemRuntimeException(
                                "工作流图校验失败：PARALLEL 节点 [" + node.name()
                                        + "] 的 @NodeNext 必须 ≥ 2，当前 " + nextCount + " 个");
                    }
                }
                case START, COMMON, ENTRANCE, EXPORT, MERGE, COMMAND, TOOL -> {
                    if (nextCount > 1) {
                        throw new SystemRuntimeException(
                                "工作流图校验失败：" + type + " 节点 [" + node.name()
                                        + "] 的 @NodeNext 仅允许 0 或 1 个，当前 " + nextCount + " 个");
                    }
                }
            }
        }
    }

    /** 规则 2：每个 START 必须可达至少一个 END */
    private static void validateReachabilityToEnd(AgentNodeRegistry registry) {
        for (AgentNodeDefinition start : registry.startNodes()) {
            if (!canReachEnd(start, registry, new HashSet<>())) {
                throw new SystemRuntimeException(
                        "工作流图校验失败：START 节点 [" + start.name()
                                + "] 无法到达任何 END 节点");
            }
        }
    }

    /**
     * 从 from 节点出发，是否可达 END。<br>
     * 避免循环：BRANCH/PARALLEL 检查所有 next，其他检查第一个 next。
     */
    private static boolean canReachEnd(AgentNodeDefinition from, AgentNodeRegistry registry, Set<String> visited) {
        if (visited.contains(from.name())) {
            return false;  // 循环，不再走
        }
        visited.add(from.name());

        if (from.type() == NodeType.END) {
            return true;
        }

        for (String nextName : from.next()) {
            AgentNodeDefinition next = registry.get(nextName).orElse(null);
            if (next != null && canReachEnd(next, registry, new HashSet<>(visited))) {
                return true;
            }
        }
        return false;
    }
}
