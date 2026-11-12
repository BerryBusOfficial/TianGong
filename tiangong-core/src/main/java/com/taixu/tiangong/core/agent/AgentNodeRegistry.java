package com.taixu.tiangong.core.agent;

import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import com.taixu.tiangong.annotations.processor.registry.Registry;
import com.taixu.tiangong.common.exception.NodeNameConflictException;
import com.taixu.tiangong.common.exception.SystemRuntimeException;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * AgentNode 节点注册表：节点名 → 节点定义。<br>
 * 同名注册抛出 {@link NodeNameConflictException}。<br>
 * 启动期结构校验（next 数量、返回类型）由 NodeProcessor 在注册前完成。
 */
public class AgentNodeRegistry implements Registry<AgentNodeDefinition> {

    private final Map<String, AgentNodeDefinition> nodes = new LinkedHashMap<>();

    @Override
    public void register(AgentNodeDefinition element) {
        String name = element.name();
        if (nodes.putIfAbsent(name, element) != null) {
            throw new NodeNameConflictException("Agent 节点名冲突: [" + name + "]（" + element.method() + "）");
        }
    }

    @Override
    public void unregister(String name) {
        nodes.remove(name);
    }

    @Override
    public Optional<AgentNodeDefinition> get(String name) {
        return Optional.ofNullable(nodes.get(name));
    }

    /** 起始节点列表（NodeType=START） */
    public Collection<AgentNodeDefinition> startNodes() {
        return nodes.values().stream()
                .filter(n -> n.type() == NodeType.START)
                .toList();
    }

    /** 按类型筛选节点 */
    public Collection<AgentNodeDefinition> byType(NodeType type) {
        return nodes.values().stream()
                .filter(n -> n.type() == type)
                .toList();
    }

    /**
     * 校验所有节点的 @NodeNext 指向都存在。<br>
     * 此校验在所有节点注册完成后调用（容器 refresh 完成后）。
     */
    public void validateNextReferences() {
        for (AgentNodeDefinition node : nodes.values()) {
            for (String nextName : node.next()) {
                if (!nodes.containsKey(nextName)) {
                    throw new SystemRuntimeException(
                            "节点 [" + node.name() + "] 的 @NodeNext 指向不存在的节点: [" + nextName + "]");
                }
            }
        }
    }

    @Override
    public Collection<AgentNodeDefinition> values() {
        return nodes.values();
    }

    @Override
    public boolean contains(String name) {
        return nodes.containsKey(name);
    }

    @Override
    public int size() {
        return nodes.size();
    }

    @Override
    public Iterator<AgentNodeDefinition> iterator() {
        return nodes.values().iterator();
    }
}
