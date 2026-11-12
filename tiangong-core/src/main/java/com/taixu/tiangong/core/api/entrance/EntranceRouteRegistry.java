package com.taixu.tiangong.core.api.entrance;

import com.taixu.tiangong.core.agent.entrance.HttpEntranceChannel;
import com.taixu.tiangong.common.enums.RequestMethod;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Entrance 路由注册表：动态管理工作流运行时注册的 HTTP 路由。<br>
 * <br>
 * 路由注册时机：工作流执行到 @ApiEntranceNode 时调用 {@link #register(EntranceRoute)}。<br>
 * 路由注销时机：工作流结束/取消时调用 {@link #unregisterWorkflow(String)}。<br>
 * <br>
 * 查询：ApiHttpServer 收到请求时调用 {@link #find(RequestMethod, String)}。
 */
public class EntranceRouteRegistry {

    /** 路由表：fullPath → route（按 method 区分） */
    private final ConcurrentMap<String, List<EntranceRoute>> routesByPath = new ConcurrentHashMap<>();

    /** workflowId → routes（用于注销时批量清理） */
    private final ConcurrentMap<String, List<EntranceRoute>> routesByWorkflow = new ConcurrentHashMap<>();

    /**
     * 注册路由。
     */
    public void register(EntranceRoute route) {
        String path = route.fullPath();
        List<EntranceRoute> bucket = routesByPath.computeIfAbsent(path, k -> new ArrayList<>());
        // ArrayList 不是线程安全的，且同路径重复注册必须被拦住：
        // 旧实现无脑 add，两条路由撞到同一 path + method 时 find() 永远只返回先注册的那条，
        // 后一条静默失效（典型场景就是两个工作流都用了 absolute 路由且重名）。
        synchronized (bucket) {
            for (EntranceRoute existing : bucket) {
                for (RequestMethod mine : route.methods()) {
                    for (RequestMethod theirs : existing.methods()) {
                        if (mine == theirs) {
                            throw new IllegalStateException(
                                    "路由冲突：[%s %s] 已被工作流 [%s] 的节点 [%s] 注册，"
                                            .formatted(mine, path, existing.workflowId(), existing.node())
                                            + "无法再注册给工作流 [%s]。"
                                            .formatted(route.workflowId())
                                            + "（若使用了 @ApiEntranceNode(absolute = true)，请保证路径唯一）");
                        }
                    }
                }
            }
            bucket.add(route);
        }
        routesByWorkflow.computeIfAbsent(route.workflowId(), k -> new ArrayList<>()).add(route);
    }

    /**
     * 按请求方法和路径查找路由。
     */
    public Optional<EntranceRoute> find(RequestMethod method, String path) {
        // 精确匹配 fullPath
        List<EntranceRoute> candidates = routesByPath.get(path);
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        for (EntranceRoute route : candidates) {
            for (RequestMethod m : route.methods()) {
                if (m == method) {
                    return Optional.of(route);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 注销工作流的所有路由（工作流结束/取消时调用）。
     */
    public void unregisterWorkflow(String workflowId) {
        List<EntranceRoute> routes = routesByWorkflow.remove(workflowId);
        if (routes == null) {
            return;
        }
        for (EntranceRoute route : routes) {
            route.close();
            routesByPath.getOrDefault(route.fullPath(), List.of())
                    .removeIf(r -> r == route);
        }
    }

    /**
     * 按 workflowId 查找该工作流的全部路由。<br>
     * <br>
     * 由 {@link HttpEntranceChannel#await} 使用：
     * 通道层拿到 workflowId 后，从注册表反查该工作流注册的路由，
     * 在其请求队列上 {@link EntranceRoute#poll(long, TimeUnit)} 取下一个请求。
     */
    public List<EntranceRoute> findByWorkflowId(String workflowId) {
        return routesByWorkflow.get(workflowId);
    }

    /**
     * 获取所有已注册路由（用于调试/监控）。
     */
    public Collection<EntranceRoute> all() {
        return routesByPath.values().stream()
                .flatMap(List::stream)
                .toList();
    }

    /**
     * 路由总数。
     */
    public int size() {
        return (int) routesByPath.values().stream().mapToInt(List::size).sum();
    }

}
