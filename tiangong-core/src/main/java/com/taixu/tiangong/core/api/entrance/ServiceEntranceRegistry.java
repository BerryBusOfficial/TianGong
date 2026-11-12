package com.taixu.tiangong.core.api.entrance;

import lombok.extern.slf4j.Slf4j;
import com.taixu.tiangong.common.agent.call.AgentCallContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 服务入口注册表：按 {@code url} 寻址的进程内服务调用中枢。<br>
 * <br>
 * 注册时机：工作流执行到 {@code @ServiceEntranceNode}（或 API 禁用模式下退化的
 * {@code @ApiEntranceNode}）时调用 {@link #register(ServiceEntranceRoute)}。<br>
 * 注销时机：工作流结束/取消时调用 {@link #unregisterWorkflow(String)}。<br>
 * <br>
 * <h2>寻址规则（对应"参数形式指定 workflow，不进 url"的设计）</h2>
 * <ul>
 *     <li>同一 url 只被一个工作流注册 → 直接命中；</li>
 *     <li>同一 url 被多个工作流注册 → 调用必须给 {@code workflow} 参数，否则报歧义错并列出候选
 *         （不静默取第一个：静默选错工作流是那种最难查的 bug）；</li>
 *     <li>同一工作流重复注册同一 url → 注册期直接报错（配置错误）。</li>
 * </ul>
 * <br>
 * <h2>异常语义</h2>
 * 本类只抛 {@link ServiceEntranceException}（桥自身故障：未注册 / 歧义 / 关闭 / 超时）。
 * 业务异常不在这里出现 —— 它以 {@link EntranceResponse#cause()} 原样回传，
 * 由调用方 rethrow（类型保真）。
 */
@Slf4j
public class ServiceEntranceRegistry {

    /** url → routes（同一 url 可被多个工作流注册，调用时用 workflow 参数消歧） */
    private final ConcurrentMap<String, List<ServiceEntranceRoute>> byUrl = new ConcurrentHashMap<>();

    /** workflowId → routes（用于注销时批量清理） */
    private final ConcurrentMap<String, List<ServiceEntranceRoute>> byWorkflow = new ConcurrentHashMap<>();

    /**
     * 注册服务地址。
     *
     * @throws ServiceEntranceException 同一工作流重复注册同一 url
     */
    public void register(ServiceEntranceRoute route) {
        List<ServiceEntranceRoute> bucket = byUrl.computeIfAbsent(route.url(), k -> new ArrayList<>());
        synchronized (bucket) {
            for (ServiceEntranceRoute existing : bucket) {
                if (existing.workflowId().equals(route.workflowId())) {
                    throw new ServiceEntranceException(
                            "服务地址冲突：[" + route.url() + "] 同一工作流 [" + route.workflowId()
                                    + "] 重复注册（新节点 [" + nodeName(route) + "]，"
                                    + "已有节点 [" + nodeName(existing) + "]）");
                }
            }
            bucket.add(route);
        }
        byWorkflow.computeIfAbsent(route.workflowId(), k -> new ArrayList<>()).add(route);
        log.debug("服务地址已注册: {}（工作流 {}，节点 {}）", route.url(), route.workflowId(), nodeName(route));
    }

    /** 节点名（容忍测试构造的无节点路由）。 */
    private static String nodeName(ServiceEntranceRoute route) {
        return route.node() == null ? "-" : route.node().name();
    }

    /**
     * 注销工作流的所有服务地址（工作流结束/取消时调用）。
     */
    public void unregisterWorkflow(String workflowId) {
        List<ServiceEntranceRoute> routes = byWorkflow.remove(workflowId);
        if (routes == null) {
            return;
        }
        for (ServiceEntranceRoute route : routes) {
            route.close();
            List<ServiceEntranceRoute> bucket = byUrl.get(route.url());
            if (bucket != null) {
                synchronized (bucket) {
                    bucket.removeIf(r -> r == route);
                    if (bucket.isEmpty()) {
                        byUrl.remove(route.url(), bucket);
                    }
                }
            }
        }
    }

    /**
     * 按 workflowId 查找该工作流的全部服务地址（通道层轮询用）。
     */
    public List<ServiceEntranceRoute> findByWorkflowId(String workflowId) {
        return byWorkflow.get(workflowId);
    }

    /**
     * 按 url 查找全部注册（同 url 多工作流时返回多条）。
     */
    public List<ServiceEntranceRoute> findByUrl(String url) {
        return byUrl.getOrDefault(url, List.of());
    }

    /**
     * 已注册的地址列表（排序，便于报错信息稳定可读）。
     */
    public List<String> urls() {
        List<String> all = new ArrayList<>(byUrl.keySet());
        all.sort(String::compareTo);
        return all;
    }

    /**
     * 服务调用：投递 body 到指定地址，阻塞等待结果。<br>
     * <br>
     * 超时会<b>主动作废响应关联</b>（与 HTTP 入口同一语义）：不作废的话工作流会一路跑到
     * 出口，把结果 complete 进一个没人接收的 Future —— 表现为"调用早就失败了，工作流还在跑"。
     *
     * @param url            服务地址（如 {@code agent://quote/price}）
     * @param workflow       目标工作流（url 被多工作流注册时必填；null/空 = 不指定）
     * @param body           请求体 JSON（可为 null，无参调用）
     * @param timeoutSeconds 等待超时（秒）
     * @throws ServiceEntranceException 未注册 / 歧义 / 已关闭 / 超时 / 中断
     */
    public EntranceResponse call(String url, String workflow, String body, long timeoutSeconds) {
        CompletableFuture<EntranceResponse> future = submit(url, workflow, body);
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(false);
            throw new ServiceEntranceException(
                    "服务调用超时（" + timeoutSeconds + "s）: [" + url + "]；"
                            + "响应关联已作废，工作流仍在运行", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceEntranceException("服务调用等待被中断: [" + url + "]", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new ServiceEntranceException("服务调用失败: [" + url + "]: " + cause.getMessage(), cause);
        }
    }

    /**
     * 非阻塞投递（void 服务方法 / fire-and-forget 用）：提交即返回，不等出口。<br>
     * 未注册 / 歧义 / 已关闭仍会立即抛 {@link ServiceEntranceException}（提交失败必须可见）；
     * 提交成功后工作流异步处理，结果经返回的 Future 送达（调用方通常忽略）。
     */
    public CompletableFuture<EntranceResponse> submit(String url, String workflow, String body) {
        ServiceEntranceRoute route = resolve(url, workflow);
        EntranceRequest request = EntranceRequest.post(url, body, AgentCallContext.current());
        if (!route.submit(request)) {
            throw new ServiceEntranceException(
                    "服务入口已关闭: [" + url + "]（工作流 " + route.workflowId() + "）");
        }
        return request.responseFuture();
    }

    /**
     * 解析目标路由：未注册 / 歧义 / 关闭都抛明确异常。
     */
    private ServiceEntranceRoute resolve(String url, String workflow) {
        List<ServiceEntranceRoute> routes = byUrl.get(url);
        if (routes == null || routes.isEmpty()) {
            String registered = urls().isEmpty() ? "（当前没有任何服务入口注册）" : "；已注册：" + urls();
            throw new ServiceEntranceException("服务入口未注册: [" + url + "]" + registered);
        }
        ServiceEntranceRoute route;
        if (workflow != null && !workflow.isBlank()) {
            route = routes.stream()
                    .filter(r -> workflow.equals(r.workflowId()))
                    .findFirst()
                    .orElseThrow(() -> new ServiceEntranceException(
                            "服务入口 [" + url + "] 没有 workflow=[" + workflow + "] 的注册；当前："
                                    + routes.stream().map(ServiceEntranceRoute::workflowId).toList()));
        } else if (routes.size() == 1) {
            route = routes.get(0);
        } else {
            throw new ServiceEntranceException(
                    "服务入口 [" + url + "] 被 " + routes.size() + " 个工作流注册，"
                            + "必须在调用中指定 workflow；当前："
                            + routes.stream().map(ServiceEntranceRoute::workflowId).toList());
        }
        if (route.isClosed()) {
            throw new ServiceEntranceException(
                    "服务入口已关闭: [" + url + "]（工作流 " + route.workflowId() + "）");
        }
        return route;
    }

    /**
     * 获取所有已注册路由（用于调试/监控）。
     */
    public Collection<ServiceEntranceRoute> all() {
        return byUrl.values().stream().flatMap(List::stream).toList();
    }

    /**
     * 服务地址总数。
     */
    public int size() {
        return byUrl.values().stream().mapToInt(List::size).sum();
    }
}
