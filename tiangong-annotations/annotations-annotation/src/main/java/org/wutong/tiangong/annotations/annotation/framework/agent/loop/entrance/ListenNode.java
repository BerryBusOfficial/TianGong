package org.wutong.tiangong.annotations.annotation.framework.agent.loop.entrance;

import org.wutong.tiangong.annotations.annotation.Alias;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.AgentNode;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.NodeType;

import java.lang.annotation.*;

/**
 * 外部事件监听入口节点：工作流执行到此节点后阻塞等待自定义事件源的通知。<br>
 * <br>
 * <h2>与 {@code @CronNode} 的区别</h2>
 * {@code @CronNode} 按时间触发；{@code @ListenNode} 按<b>外部世界</b>触发
 * （MQ 消息、文件变更、Webhook 回调、IM 推送、硬件信号……）。时间由框架算，
 * 事件源由用户写 —— 所以这里要指定一个 {@link ListenNodeHandler}。
 *
 * <h2>方法签名约定</h2>
 * <pre>
 * &#64;ListenNode(listenOn = OrderPaidHandler.class)
 * void onOrderPaid(AgentContext ctx, OrderPaidEvent event) { ... }
 * </pre>
 * 第一个参数固定为 AgentContext；第二个参数是 handler 通过
 * {@code emitter.accept(payload)} 投出的对象（按 JSON 反序列化成你声明的类型）。
 * 不写第二个参数也可以。
 *
 * <h2>handler 的生命周期</h2>
 * <ol>
 *     <li>工作流第一次到达本节点：框架从容器取 handler Bean，
 *         调 {@link ListenNodeHandler#listen}（<b>注册完回调必须立刻返回</b>，
 *         不要在这个方法里阻塞等待事件）</li>
 *     <li>事件到达：handler 调 {@code emitter.accept(payload)} → 工作流跑一轮</li>
 *     <li>工作流退出本节点（取消 / 关闭 / 异常）：框架调
 *         {@link ListenNodeHandler#dispose()}，handler 在此注销回调、释放连接</li>
 * </ol>
 *
 * <h2>handler 必须是 Bean</h2>
 * {@link #listenOn()} 给的是 Class，框架按类型从容器取实例 ——
 * <b>不会</b>反射 new（那样 {@code @Inject} 字段会静默为 null）。
 * 请给实现类标 {@code @Component}。同一个 handler 被多条工作流引用时，
 * {@code listen()} 会被调用多次（每次带不同的上下文与 emitter），实现类需自行区分。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@AgentNode(type = NodeType.ENTRANCE)
public @interface ListenNode {

    @Alias("name")
    String value() default "";

    @Alias("value")
    String name() default "";

    /**
     * 事件源 handler 类型。实现类须实现 {@link ListenNodeHandler} 并注册为容器 Bean。
     */
    Class<? extends ListenNodeHandler> listenOn();
}
