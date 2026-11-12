package org.wutong.tiangong.annotations.annotation.framework.agent.loop.entrance;

import org.wutong.tiangong.annotations.annotation.Alias;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.AgentNode;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.NodeType;

import java.lang.annotation.*;

/**
 * API 出口节点：工作流执行到此节点时，将方法返回值序列化为 JSON 作为 HTTP 响应。<br>
 * <br>
 * 响应通过 EntranceRoute 的响应关联表回传给阻塞等待的 HTTP 请求。<br>
 * <br>
 * 方法签名约定：<br>
 * <pre>
 * &#64;ApiExportNode(status = 200)
 * Result process(AgentContext ctx) { ... }
 * </pre>
 * 第一个参数固定为 AgentContext。返回值序列化为 JSON 响应体，void 返回 204。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@AgentNode(type = NodeType.EXPORT)
public @interface ApiExportNode {

    @Alias("name")
    String value() default "";

    @Alias("value")
    String name() default "";

    /**
     * 响应 Content-Type，默认 application/json。
     */
    String[] produces() default {};

    /**
     * HTTP 状态码，默认 200。
     */
    int status() default 200;

}
