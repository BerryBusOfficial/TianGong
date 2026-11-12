package org.wutong.tiangong.core.api.entrance;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP 请求投递到 EntranceNode 的数据载体。<br>
 * <br>
 * 由 ApiHttpServer 构造，投递到 {@link EntranceRoute#submit(EntranceRequest)}，
 * 工作流线程从队列取出后处理。<br>
 * <br>
 * 响应通过 {@link #responseFuture} 回传：ExportNode 执行后完成该 Future。
 *
 * @param method        HTTP 方法
 * @param path          请求路径
 * @param body          请求体（JSON 字符串）
 * @param headers       请求头
 * @param queryParams   查询参数
 * @param contextId     fork 后的上下文 ID（用于响应关联）
 * @param responseFuture 响应 Future（由 ExportNode 完成）
 */
public record EntranceRequest(
        String method,
        String path,
        String body,
        Map<String, String> headers,
        Map<String, String> queryParams,
        String contextId,
        CompletableFuture<EntranceResponse> responseFuture
) {

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
        return new EntranceRequest(
                "POST", path, body, Map.of(), Map.of(),
                null, new CompletableFuture<>()
        );
    }

}
