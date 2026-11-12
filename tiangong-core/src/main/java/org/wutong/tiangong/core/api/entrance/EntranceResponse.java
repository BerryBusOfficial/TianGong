package org.wutong.tiangong.core.api.entrance;

/**
 * EntranceNode 产出的响应。<br>
 * 由 AgentLoopExecutor 在执行出口节点后构造，完成 {@link EntranceRequest#responseFuture()}。<br>
 * <br>
 * <h2>cause 字段的用途</h2>
 * HTTP 链路只用 {@code status/body/contentType}；{@link #cause()} 是<b>进程内服务调用</b>
 * 的类型保真通道：入口/链路节点抛出的原始异常挂在这里原样交给调用方，让对侧的异常处理
 * 体系（{@code @NodeExceptionHandler} / Spring 侧处理器）能按真实类型命中。<br>
 * HTTP 序列化忽略该字段。
 *
 * @param status HTTP 状态码
 * @param body   响应体（JSON 字符串），null 表示 204 无内容
 * @param contentType 响应 Content-Type
 * @param cause  原始异常（进程内调用专用，HTTP 链路为 null）
 */
public record EntranceResponse(
        int status,
        String body,
        String contentType,
        Throwable cause
) {

    /** 兼容构造：无附加异常（HTTP 链路永远用它） */
    public EntranceResponse(int status, String body, String contentType) {
        this(status, body, contentType, null);
    }

    /** 快捷构造：200 + JSON body */
    public static EntranceResponse ok(String body) {
        return new EntranceResponse(200, body, "application/json; charset=UTF-8");
    }

    /** 快捷构造：204 无内容 */
    public static EntranceResponse noContent() {
        return new EntranceResponse(204, null, null);
    }

    /** 快捷构造：自定义状态码 + body */
    public static EntranceResponse of(int status, String body) {
        return new EntranceResponse(status, body, "application/json; charset=UTF-8");
    }

    /** 快捷构造：错误响应 */
    public static EntranceResponse error(int status, String message) {
        return new EntranceResponse(status, message, "text/plain; charset=UTF-8");
    }

    /**
     * 错误响应 + 原始异常。<br>
     * 进程内服务调用方按 {@link #cause()} 原样 rethrow（类型保真）；HTTP 链路忽略 cause。
     */
    public static EntranceResponse error(int status, String message, Throwable cause) {
        return new EntranceResponse(status, message, "text/plain; charset=UTF-8", cause);
    }

}
