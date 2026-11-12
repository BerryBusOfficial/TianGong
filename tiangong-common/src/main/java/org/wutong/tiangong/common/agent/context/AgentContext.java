package org.wutong.tiangong.common.agent.context;

import org.wutong.tiangong.common.enums.Namespace;
import org.wutong.tiangong.common.enums.ResourceType;
import org.wutong.tiangong.common.exception.CheckpointException;
import org.wutong.tiangong.common.util.IdGenerator;
import org.wutong.tiangong.common.util.JsonUtil;

/**
 * Agent 执行上下文接口。<br>
 * 贯穿整个 Agent Loop 的状态载体，节点方法读取/修改此对象。<br>
 * <br>
 * 上下文标识与生命周期：
 * <ul>
 *     <li>{@link #getId()}：上下文自身 ID（用于 trace/日志）</li>
 *     <li>{@link #workflowId()}：所属工作流实例 ID，由 {@code WorkflowScheduler} 启动时分配</li>
 *     <li>{@link #isCancelled()}：协作式中断标志，框架在每个节点执行前检查，true 则退出工作流</li>
 * </ul>
 */
public interface AgentContext {

    /**
     * 上下文唯一标识。<br>
     * 实现类可持有 {@code id} 字段并在构造时赋值，否则每次调用生成新 ID。
     */
    default String getId() {
        return IdGenerator.generate(Namespace.AGENT, ResourceType.CONTEXT, 32);
    }

    /**
     * 工作流实例 ID：由 {@code WorkflowScheduler} 启动工作流时通过 {@link #bindWorkflow(String)} 注入。<br>
     * 用于跨工作流追踪、日志关联、{@code WorkflowQueryAPI} 查询与取消。
     */
    String workflowId();

    /**
     * 绑定工作流 ID（框架内部调用，用户不应直接调用）。
     */
    void bindWorkflow(String workflowId);

    /**
     * 工作流是否被取消（协作式中断）。<br>
     * 框架在 {@code AgentLoopExecutor} 每个节点执行前检查，true 则抛出
     * {@code WorkflowCancelledException} 退出工作流。
     */
    boolean isCancelled();

    /**
     * 取消当前工作流（协作式中断）。<br>
     * 设置标志后，工作流在下一个节点边界退出。
     */
    void cancel();

    /**
     * 深拷贝上下文：PARALLEL 节点为每个分支创建独立副本，避免状态竞争。<br>
     * 默认基于 JsonUtil 序列化/反序列化实现，子类可重写以提供更高效的拷贝策略。<br>
     * 拷贝失败时抛 {@link CheckpointException}，<b>不再静默返回 this</b>——
     * 旧实现返回 this 会让 PARALLEL 分支退化为"多线程共享同一上下文"，
     * 是静默的数据竞争；宁可抛出暴露问题，也不能让分支在共享状态下继续跑。<br>
     * <br>
     * 注意：拷贝会清除 {@code workflowId} 和 {@code cancelFlag}，
     * 分支副本不归属任何工作流，不受取消控制（分支独立运行到 MERGE）。<br>
     * <br>
     * <b>本方法不是快照原语</b>：其语义是"游离的分支副本"，会清空 workflowId 与取消标志。
     * 状态存档请使用 {@link org.wutong.tiangong.common.agent.checkpoint.ContextSnapshotter}——
     * 那是可替换的扩展点，不假设上下文一定可 JSON 序列化（检查点契约 A3）。
     */
    default AgentContext copy() {
        try {
            String json = JsonUtil.toJson(this);
            AgentContext copy = (AgentContext) JsonUtil.fromJson(json, this.getClass());
            copy.bindWorkflow(null);  // 分支副本不归属任何工作流，不受取消控制
            return copy;
        } catch (Exception e) {
            throw new CheckpointException(
                    "上下文拷贝失败（类型: " + this.getClass().getName()
                            + "）。PARALLEL 分支与多请求并发依赖独立副本，"
                            + "拷贝失败会导致两者共享同一状态。"
                            + "若上下文含不可 JSON 序列化的字段，请重写 copy()。", e);
        }
    }

    /**
     * 整条工作流初始化时执行一次（在进入第一个节点之前）。<br>
     * <br>
     * <b>由框架调用</b>（{@code AgentLoopExecutor.execute} 入口），应用不应在节点方法里手动调用
     * —— 手动调用会让「初始化了几次」取决于节点怎么走，难以推理。
     *
     * <h2>与 {@link #afterEnd()} 的分工</h2>
     * <table border="1">
     *     <tr><th></th><th>init</th><th>afterEnd</th><th>destroy</th></tr>
     *     <tr><td>时机</td><td>工作流启动，一次</td><td>每个 END 节点后</td><td>工作流终止，一次</td></tr>
     *     <tr><td>典型用途</td><td>装载系统提示词、建历史</td><td>清理本轮临时状态</td><td>释放句柄、落盘</td></tr>
     * </table>
     *
     * <b>为什么必须区分 afterEnd 与 destroy</b>：STDIN / HTTP 会话型工作流跑在
     * {@code SESSION_LOOP} 上，<b>每轮对话都会走到 END 节点</b>（一轮 = 取输入 → 跑到 END）。
     * 若把善后写在 destroy 里，第一轮对话结束就会释放上下文，第二轮输入时状态已空。
     */
    default void init() {
        // 默认无操作
    }

    /**
     * 每个 END 节点执行之后调用，用于<b>本轮的善后</b>。<br>
     * <br>
     * 在会话型工作流（{@code SESSION_LOOP}）中，本方法<b>每轮都会被调用</b>，
     * 而 {@link #destroy()} 只在整条工作流终止时调用一次。需要「释放上下文」这类
     * 终结语义时应覆写 {@code destroy}，不要写在这里。<br>
     * <br>
     * 典型用途：清空本轮的工具调用残留（nowTool / functionArgsMap）、截断历史、
     * 写一轮的审计日志。<br>
     * <br>
     * 调用点覆盖执行器的三处 END 出口（主循环 / continueFrom / 分支执行），
     * 因此无论工作流以哪种路径走到 END 都会触发。
     */
    default void afterEnd() {
        // 默认无操作
    }

    /**
     * 整条工作流终止时执行一次（所有退出路径：正常结束 / 会话退出 / 取消 / 异常）。<br>
     * <br>
     * 由 {@code AgentLoopExecutor.execute} 的 try/finally 调用，因此即使工作流
     * 抛异常或被取消也会执行 —— 这是释放外部资源的最后机会。<br>
     * <br>
     * 默认语义应为「释放上下文」（清空累积状态、关闭句柄、落盘），但默认实现为空，
     * 由应用按需覆写。<b>不要在这里放「每轮都要做」的逻辑</b>，那属于 {@link #afterEnd()}。
     */
    default void destroy() {
        // 默认无操作
    }

    /**
     * 从检查点还原之后的回调：重新装配「不属于状态」的协作资源（检查点契约 D1）。<br>
     * <br>
     * <b>为什么需要它</b>：还原出的上下文是一个普通对象，<b>没有经过容器注入</b>。
     * 那些被 {@code @SnapshotIgnore} 排除的成员（容器单例 Bean、外部句柄）在还原后是
     * 未初始化的（null / 默认值），直接使用会 NPE 或读到空值。
     * 框架在每次从快照还原后调用本方法，由应用负责把它们取回来。<br>
     * <br>
     * <b>调用时机</b>：{@code CheckpointService} 的每次还原之后 ——
     * 因此它同时覆盖 {@code AgentRuntime.resume}（恢复执行）与
     * {@code CheckpointService.stateAt}（时间旅行回看）。<br>
     * <br>
     * 默认实现为空（无忽略成员时无需做任何事）。<br>
     * <br>
     * 实现示例（应用层）：
     * <pre>{@code
     * @Override
     * public void afterRestore() {
     *     var app = ContextHolder.getApplication();
     *     if (app != null && app.getContainer() != null) {
     *         setHistory(app.getContainer().getBean(SessionHistory.class));
     *     }
     * }
     * }</pre>
     */
    default void afterRestore() {
        // 默认无操作：没有 @SnapshotIgnore 成员的上下文无需重新装配
    }

}
