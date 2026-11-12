package com.taixu.tiangong.core.api.entrance;

import com.taixu.tiangong.common.agent.call.AgentCallContext;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP/服务请求投递到 EntranceNode 的数据载体。<br>
 * <br>
 * 由 ApiHttpServer / 服务入口构造，投递到 {@link EntranceRoute#submit(EntranceRequest)}，
 * 工作流线程从队列取出后处理。<br>
 * <br>
 * 响应通过 {@link #responseFuture} 回传：ExportNode 执行后完成该 Future。<br>
 * {@link #callContext} 在 fork 线程执行前显式绑定到 ThreadLocal，工具权限判断用它；
 * 它不进入工作流状态，也不会被检查点序列化。
 *
 * @param method         HTTP 方法
 * @param path           请求路径
 * @param body           请求体（JSON 字符串）
 * @param headers        请求头
 * @param queryParams    查询参数
 * @param contextId      fork 后的上下文 ID（用于响应关联）
 * @param responseFuture 响应 Future（由 ExportNode 完成）
 * @param callContext    调用者身份/权限；无身份头时为 null
 */
public record EntranceRequest(
        String method,
        String path,
        String body,
        Map<String, String> headers,
        Map<String, String> queryParams,
        String contextId,
        CompletableFuture<EntranceResponse> responseFuture,
        AgentCallContext callContext
) {

    /** 兼容构造：无调用者上下文（既有代码/单测） */
    public EntranceRequest(String method, String path, String body,
                           Map<String, String> headers, Map<String, String> queryParams,
                           String contextId, CompletableFuture<EntranceResponse> responseFuture) {
        this(method, path, body, headers, queryParams, contextId, responseFuture, null);
    }

    /**
     * 快捷构造：无 body 的 GET 请求。
     */
    public static EntranceRequest get(String path, Map<String, String> queryParams) {
        return new EntranceRequest(
                "GET", path, null, Map.of(), queryParams != null ? queryParams : Map.of(),
                null, new CompletableFuture<>()
        );
    }

    /**
     * 快捷构造：带 body 的 POST 请求。
     */
    public static EntranceRequest post(String path, String body) {
        return post(path, body, AgentCallContext.current());
    }

    /**
     * 带调用者上下文的 POST 请求（服务入口/进程内调用用）。
     */
    public static EntranceRequest post(String path, String body, AgentCallContext callContext) {
        return new EntranceRequest(
                "POST", path, body, Map.of(), Map.of(),
                null, new CompletableFuture<>(), callContext
        );
    }

}
