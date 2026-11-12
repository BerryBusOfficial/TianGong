package org.wutong.tiangong.annotations.annotation.framework.agent.loop.entrance;

import org.wutong.tiangong.annotations.annotation.Alias;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.AgentNode;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.NodeType;

import java.lang.annotation.*;

/**
 * 服务出口节点：工作流执行到此节点时，将方法返回值序列化为 JSON 作为<b>服务调用结果</b>回传。<br>
 * <br>
 * 与 {@link ApiExportNode} 的区别：没有 HTTP 状态码/Content-Type —— 结果经
 * {@code EntranceResponse} 直接交给阻塞等待的调用方（进程内，无序列化传输）。<br>
 * <br>
 * 方法签名约定：第一个参数固定为 AgentContext。返回值：
 * <ul>
 *     <li>{@code void}：无内容（调用方按 void 处理）</li>
 *     <li>引用类型：序列化为 JSON，调用方按接口方法的返回类型反序列化</li>
 * </ul>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@AgentNode(type = NodeType.EXPORT)
public @interface ServiceExportNode {

    @Alias("name")
    String value() default "";

    @Alias("value")
    String name() default "";
}
