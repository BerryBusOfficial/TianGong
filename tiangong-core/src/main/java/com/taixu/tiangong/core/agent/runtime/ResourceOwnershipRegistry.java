package com.taixu.tiangong.core.agent.runtime;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 资源占用注册表：防御性检测独占资源（如 stdin）的并发占用。<br>
 * <br>
 * ENTRANCE 节点访问独占资源前调用 {@link #tryAcquire}，失败则抛异常，框架不兜底。<br>
 * 工作流结束/异常时调用 {@link #release} 释放。<br>
 * <br>
 * 设计哲学：明明只有一个 stdin，同时写多个 ENTRANCE 就是用户的责任，框架只负责暴露冲突。
 */
public class ResourceOwnershipRegistry {

    /** resource → 持有者 workflowId */
    private final ConcurrentMap<String, String> owners = new ConcurrentHashMap<>();

    /**
     * 尝试占用资源。被占用时抛 {@link IllegalStateException}。
     *
     * @param resource   资源标识（如 "stdin"、"stdout"）
     * @param workflowId 工作流实例 ID
     * @return true 表示占用成功
     */
    public boolean tryAcquire(String resource, String workflowId) {
        String existing = owners.putIfAbsent(resource, workflowId);
        if (existing != null && !existing.equals(workflowId)) {
            throw new IllegalStateException(
                    "资源 [" + resource + "] 已被工作流 [" + existing + "] 占用，"
                            + "当前工作流 [" + workflowId + "] 无法获取。"
                            + "同一时刻仅允许一个工作流占用此资源。");
        }
        return true;
    }

    /**
     * 释放资源占用。
     *
     * @param resource   资源标识
     * @param workflowId 工作流实例 ID
     */
    public void release(String resource, String workflowId) {
        owners.remove(resource, workflowId);
    }

    /**
     * 查询资源当前持有者。
     */
    public Optional<String> owner(String resource) {
        return Optional.ofNullable(owners.get(resource));
    }

    /**
     * 释放指定工作流占用的所有资源（工作流结束时调用）。
     */
    public void releaseAll(String workflowId) {
        owners.entrySet().removeIf(e -> e.getValue().equals(workflowId));
    }
}
