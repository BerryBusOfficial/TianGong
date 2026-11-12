package org.wutong.tiangong.core.agent.entrance;

import org.wutong.tiangong.common.agent.context.AgentContext;

/**
 * 入口通道：把"工作流如何从外部世界取输入"作为框架扩展点。<br>
 * <br>
 * 三种典型形态：
 * <ul>
 *     <li>{@link #SESSION_LOOP STDIN 串行会话} —— 每一轮（取输入 → 跑节点链到 END）循环
 *         处理；用于交互式 CLI / REPL 风格的工作流</li>
 *     <li>{@link #FORK_PER_INPUT HTTP 每请求 fork} —— 主线程驻留等待，每来一个请求
 *         fork 副本在新线程里跑；用于服务端 HTTP 入口</li>
 *     <li>{@link #WAIT_ONCE 一次性等待} —— 取一次输入就继续主流程；用于人工审批、确认对话框</li>
 * </ul>
 *
 * <h2>为什么是泛型 {@code I}</h2>
 * 不同通道的"输入"语义不同：STDIN 是 {@code String}（用户原始文本），
 * HTTP 是 {@code EntranceRequest}（含 headers / body / params），未来 GUI 通道可能是
 * 结构化回调对象。泛型让每个通道自己定义返回类型，框架不强制统一"Input"抽象。
 *
 * <h2>通道实现必须遵守的契约</h2>
 * <ul>
 *     <li><b>生命周期</b>：{@link #start()} 在框架初始化时调一次；
 *         {@link #close()} 在框架关闭时调一次（shutdown hook）。通道可在
 *         {@code start()} 起后台线程、注册路由；在 {@code close()} 里收尾</li>
 *     <li><b>协作式取消</b>：{@code await()} 必须在合理时间内（建议 ≤ 200ms 轮询粒度）
 *         响应 {@code AgentContext#isCancelled()} 与通道自身关闭，避免阻塞导致
 *         工作流取消/关机信号打不进阻塞点</li>
 *     <li><b>无输入语义</b>：返回 {@code null} 表示通道已结束（EOF / 关闭 / 拒绝后续）；
 *         框架据此结束入口节点</li>
 *     <li><b>不写检查点</b>：通道实现不要自己写检查点；检查点写入由执行器在调用
 *         {@code await()} 前后统一处理（不变量 A6 / A8）</li>
 * </ul>
 *
 * <h2>为什么 {@link #await()} 接收 {@code workflowId} 而非 {@code AgentNodeDefinition}</h2>
 * 通道实现不依赖节点本身（节点只是消费输入的执行点）；通道只需要知道"哪个工作流
 * 在等"，用于日志、路由键、与上下文无关的账务。{@code AgentNodeDefinition} 由
 * 执行器持有，调用 {@code await()} 后再传给节点方法。
 *
 * @param <I> 通道返回的输入类型
 */
public interface EntranceChannel<I> {

    /**
     * 通道唯一标识，注解（如未来 {@code @ApproveNode}）上按名引用。
     */
    String name();

    /**
     * 框架启动时调用一次。通道可在此起后台线程、注册路由、连接外部资源。<br>
     * 默认空操作。
     */
    default void start() {}

    /**
     * 框架关闭时调用一次（shutdown hook）。通道必须在此停止接受新输入、释放资源。<br>
     * 关闭后未结束的 {@code await()} 调用应在 ≤ 轮询粒度 内返回 {@code null}。<br>
     * 默认空操作。
     */
    default void close() {}

    /**
     * 阻塞取得一次外部输入。<br>
     * <br>
     * 返回 {@code null} 表示通道已结束（EOF / 关闭 / 拒绝后续），框架据此结束入口节点。<br>
     * 返回非 {@code null} 表示取得一次有效输入，框架会把它传给入口节点方法。
     *
     * @param workflowId 当前等待输入的工作流实例 ID
     * @param context    工作流上下文，用于检查取消标志
     * @return 输入内容；{@code null} 表示通道已结束
     * @throws InterruptedException 等待被中断（通道应保证 {@code close()} 后
     *                               调用方能从阻塞中退出）
     */
    I await(String workflowId, AgentContext context) throws InterruptedException;

    /**
     * 把通道取到的输入转成入口节点方法的 String body。<br>
     * <br>
     * 入口节点方法签名约定（{@code AgentNodeDefinition#invokeEntrance}）：第二个
     * 参数为 {@code String}（用户原始文本或 JSON 字符串）。通道实现负责把自己
     * 的输入形态（用户文本 / HTTP body / 审批决定 JSON）转成这个 String。<br>
     * <br>
     * 通道"决定如何传给节点"是合理的：只有通道知道自己的输入语义。
     * 比如 STDIN 是用户原始文本（不解析），HTTP 是 JSON 字符串（要解析），
     * 审批是结构化对象序列化的 JSON。
     *
     * @param input {@link #await} 返回的输入
     * @return invokeEntrance 接受的 String body
     */
    String toBody(I input);

    /**
     * body 是否按 JSON 解析。<br>
     * <br>
     * 解析语义见 {@code AgentNodeDefinition#invokeEntrance}：{@code false} 时
     * 直接把 body 当字符串注入（STDIN 风格），{@code true} 时按参数类型做
     * JSON 反序列化（HTTP / 审批风格）。
     * <p>默认 {@code true}（HTTP / 审批都是 JSON）；STDIN 覆写为 {@code false}。
     */
    default boolean isJsonBody() {
        return true;
    }
}