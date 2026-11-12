package com.taixu.tiangong.core.agent;

import com.taixu.tiangong.core.agent.runtime.WorkflowScheduler;
import com.taixu.tiangong.core.checkpoint.ResumePoint;
import lombok.extern.slf4j.Slf4j;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.entrance.ApiEntranceNode;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.entrance.ApiExportNode;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.entrance.ApproveNode;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.entrance.CronNode;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.entrance.ListenNode;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.entrance.ListenNodeHandler;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.entrance.ServiceEntranceNode;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.entrance.ServiceExportNode;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.entrance.StdinEntranceNode;
import com.taixu.tiangong.common.agent.call.AgentCallContext;
import com.taixu.tiangong.common.agent.context.AgentContext;
import com.taixu.tiangong.common.sandbox.CommandLine;
import com.taixu.tiangong.common.thread.VirtualThreads;
import com.taixu.tiangong.common.util.JsonUtil;
import com.taixu.tiangong.core.agent.runtime.WorkflowCancelledException;
import com.taixu.tiangong.core.container.context.ContextHolder;
import com.taixu.tiangong.core.sandbox.CommandLineResolver;
import com.taixu.tiangong.core.sandbox.SandboxRegistry;
import com.taixu.tiangong.core.api.entrance.EntranceRequest;
import com.taixu.tiangong.core.api.entrance.EntranceResponse;
import com.taixu.tiangong.core.api.entrance.EntranceRoute;
import com.taixu.tiangong.core.api.entrance.EntranceRouteRegistry;
import com.taixu.tiangong.core.api.entrance.ServiceEntranceRegistry;
import com.taixu.tiangong.core.api.entrance.ServiceEntranceRoute;
import com.taixu.tiangong.core.agent.entrance.ApprovalEntranceChannel;
import com.taixu.tiangong.core.agent.entrance.CronEntranceChannel;
import com.taixu.tiangong.core.agent.entrance.CronSubscription;
import com.taixu.tiangong.core.agent.entrance.EntranceChannel;
import com.taixu.tiangong.core.agent.entrance.ListenEntranceChannel;
import com.taixu.tiangong.core.agent.entrance.EntranceChannelRegistry;
import com.taixu.tiangong.core.agent.entrance.EntranceMode;
import com.taixu.tiangong.core.agent.entrance.HttpEntranceChannel;
import com.taixu.tiangong.core.agent.entrance.ServiceEntranceChannel;
import com.taixu.tiangong.core.agent.entrance.StdinEntranceChannel;
import com.taixu.tiangong.core.checkpoint.CheckpointRecord;
import com.taixu.tiangong.core.checkpoint.CheckpointService;
import com.taixu.tiangong.core.container.Container;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * AgentLoop 节点执行引擎：从指定 START 节点出发，沿 @NodeNext 跳转执行节点方法。<br>
 * <br>
 * 节点类型行为：
 * <ul>
 *     <li>START：起始节点，由 WorkflowScheduler 指定具体入口</li>
 *     <li>COMMON：执行方法 → 按 @NodeNext 跳转</li>
 *     <li>ENTRANCE：阻塞等待外部请求（HTTP/STDIN）；HTTP 每请求 fork 上下文副本执行，
 *         STDIN 则串行处理每轮输入（一轮跑到 END 才回到本节点重新阻塞）</li>
 *     <li>EXPORT：执行方法 → 将返回值作为 HTTP 响应回传 → 继续执行后续节点</li>
 *     <li>BRANCH：方法返回 String（下一个节点名，必须在 @NodeNext 范围内）</li>
 *     <li>PARALLEL：对每个 @NodeNext 分支 fork 虚拟线程，用 context.copy() 独立执行，收集结果</li>
 *     <li>MERGE：接收并行分支结果（List&lt;AgentContext&gt;），合并后继续</li>
 *     <li>END：执行方法 → 结束本轮工作流</li>
 * </ul>
 * <br>
 * 协作式中断：每个节点执行前检查 {@code context.isCancelled()}，
 * true 则抛 {@link WorkflowCancelledException} 退出。<br>
 * <br>
 * EntranceNode 机制：工作流执行到 ENTRANCE 节点时，动态注册路由到
 * {@link EntranceRouteRegistry}，然后阻塞等待请求。请求到达后 fork 上下文副本，
 * 通过 {@link #CURRENT_REQUEST} ThreadLocal 携带请求关联，EXPORT 节点据此回传响应。
 */
@Slf4j
public class AgentLoopExecutor {

    /** 默认最大迭代次数：兜底死循环 */
    public static final int DEFAULT_MAX_ITERATIONS = 100;

    private final AgentNodeRegistry registry;
    private final NodeExceptionAdviceRegistry adviceRegistry;
    private final int maxIterations;
    private final ExecutorService parallelExecutor;

    /** Entrance 路由注册表（可选，null 表示无 API 能力） */
    private final EntranceRouteRegistry routeRegistry;

    /**
     * 检查点服务（可选，null 表示未开启 {@code @EnableCheckpoint}）。<br>
     * 开启后，工作流<b>主执行线程</b>在每个节点边界写入 STARTED / COMPLETED / FAILED 记录
     * （检查点契约 A1/A2）。非主线程（PARALLEL 分支、HTTP 请求 fork）不写检查点（A6），
     * 该约束由调用点显式传参 + {@code workflowId} 守卫双重保证。
     */
    private volatile CheckpointService checkpointService;

    /**
     * STDIN 入口通道：{@code System.in} 的唯一读取与行队列搬到了独立类。
     * 懒加载：第一次有 {@code @StdinEntranceNode} 被执行时才启动输入泵，
     * 纯 HTTP / 审批等工作流不会触发任何 STDIN 相关副作用。
     */
    private final StdinEntranceChannel stdinChannel = new StdinEntranceChannel();

    /**
     * HTTP 入口通道：路由注册 + 取请求收编到 EntranceChannel。<br>
     * HTTP 服务器本身仍由 {@code ApplicationFrameworkContext} 创建与关闭，
     * 通道只持有路由注册表用于"取请求"。<br>
     * 懒加载：第一次有 {@code @ApiEntranceNode} 被执行时才创建（与 routeRegistry 一致）。
     */
    private HttpEntranceChannel apiChannel;

    /**
     * 服务入口注册表（可选，由框架装配）：进程内服务调用（{@code @ServiceEntranceNode}
     * 与 API 禁用模式下退化的 {@code @ApiEntranceNode}）的寻址中枢。<br>
     * 未装配时服务入口节点会明确报错 —— 而不是静默等待一个永远不会到达的请求。
     */
    private volatile ServiceEntranceRegistry serviceEntranceRegistry;

    /**
     * 服务入口通道：把地址注册 + 取请求收编到 {@link EntranceChannel}
     * （与 {@link HttpEntranceChannel} 同骨架，区别只在寻址）。
     */
    private volatile ServiceEntranceChannel serviceChannel;

    /**
     * API 入口是否被禁用（宿主模式，如 Spring 集成）。<br>
     * true 时 {@code @ApiEntranceNode} 退化为服务入口（url = agent:// + route）、
     * {@code @ApiExportNode} 按服务出口回传；由框架启动期校验后设置。
     */
    private volatile boolean apiForbidden;

    /**
     * 审批入口通道：阻塞等待人工审批决定。<br>
     * 单例多工作流共享。上层应用调 {@code approvalChannel.submit(decision)} 投递决定。
     */
    private final ApprovalEntranceChannel approvalChannel = new ApprovalEntranceChannel();

    /**
     * 定时入口通道：{@code @CronNode} 的"取输入"实现，自带调度线程。<br>
     * 单例多工作流共享（订阅按 workflowId 区分）。
     */
    private final CronEntranceChannel cronChannel = new CronEntranceChannel();

    /**
     * 外部事件监听通道：{@code @ListenNode} 的"取输入"实现。
     * 单例多工作流共享（绑定按 workflowId 区分）。
     */
    private final ListenEntranceChannel listenChannel = new ListenEntranceChannel();

    /**
     * IoC 容器引用（可选，null 表示未装配）。<br>
     * 目前只为一个用途：按类型解析 {@code @ListenNode(listenOn = ...)} 的 handler
     * —— handler 必须是容器 Bean（反射 new 会让 {@code @Inject} 字段静默为 null）。
     */
    private volatile Container container;

    /**
     * 入口通道注册表：按名持有全部通道，让注解上的 {@code channel()} 真正起作用。<br>
     * <br>
     * 之前三种内置通道只是本类的硬编码字段，{@code @ApproveNode(channel = "...")}
     * 写了也不被读取 —— 文档承诺"可按名替换为自己的通道实现"，代码里从不按名查找。
     * 这里把它补上：内置通道在构造时注册，应用可再注册自己的（同名即替换）。
     */
    private final EntranceChannelRegistry channelRegistry = new EntranceChannelRegistry();

    /**
     * 获取审批入口通道（供上层应用与测试投递审批决定用）。
     */
    public ApprovalEntranceChannel approvalChannel() {
        return approvalChannel;
    }

    /**
     * 获取入口通道注册表：应用可 {@code register(...)} 自己的通道
     * （如邮件审批 / IM 审批），再在 {@code @ApproveNode(channel = "...")} 上按名引用。
     */
    public EntranceChannelRegistry channelRegistry() {
        return channelRegistry;
    }

    /** 获取定时入口通道（供测试与监控：可查订阅数、投递/丢弃计数） */
    public CronEntranceChannel cronChannel() {
        return cronChannel;
    }

    /** 获取监听入口通道（供测试与监控：可查绑定数、积压数） */
    public ListenEntranceChannel listenChannel() {
        return listenChannel;
    }

    /**
     * 装配 IoC 容器。容器 refresh 之后由框架调用。<br>
     * 未装配时 {@code @ListenNode} 无法解析 handler，会明确报错而不是静默降级。
     */
    public void setContainer(Container container) {
        this.container = container;
    }

    /**
     * 装配服务入口注册表：创建通道并注册进通道注册表。<br>
     * 由框架启动时调用；未装配时 {@code @ServiceEntranceNode} 会明确报错。
     */
    public void setServiceEntranceRegistry(ServiceEntranceRegistry registry) {
        this.serviceEntranceRegistry = registry;
        this.serviceChannel = new ServiceEntranceChannel(registry);
        channelRegistry.register(serviceChannel);
    }

    /**
     * 声明 API 入口被禁用（宿主模式）。<br>
     * 由框架启动期校验（{@code tiangong.entrance.api.error.enable=false} 的退化路径）后设置。
     */
    public void setApiForbidden(boolean apiForbidden) {
        this.apiForbidden = apiForbidden;
    }

    /**
     * API 入口是否处于禁用（退化）模式 —— 供启动期校验后的观测与测试断言。
     */
    public boolean isApiForbidden() {
        return apiForbidden;
    }

    /**
     * 当前线程关联的 Entrance 请求。<br>
     * fork 的执行线程设置此值，EXPORT 节点读取此值回传响应。
     */
    static final ThreadLocal<EntranceRequest> CURRENT_REQUEST = new ThreadLocal<>();

    public AgentLoopExecutor(AgentNodeRegistry registry) {
        this(registry, null, DEFAULT_MAX_ITERATIONS, null);
    }

    public AgentLoopExecutor(AgentNodeRegistry registry, NodeExceptionAdviceRegistry adviceRegistry) {
        this(registry, adviceRegistry, DEFAULT_MAX_ITERATIONS, null);
    }

    public AgentLoopExecutor(AgentNodeRegistry registry, NodeExceptionAdviceRegistry adviceRegistry,
                             int maxIterations) {
        this(registry, adviceRegistry, maxIterations, null);
    }

    public AgentLoopExecutor(AgentNodeRegistry registry, NodeExceptionAdviceRegistry adviceRegistry,
                             int maxIterations, EntranceRouteRegistry routeRegistry) {
        this.registry = registry;
        this.adviceRegistry = adviceRegistry;
        this.maxIterations = maxIterations;
        this.parallelExecutor = VirtualThreads.newPerTaskExecutor();
        this.routeRegistry = routeRegistry;
        // 内置通道按名注册，@ApproveNode(channel = "...") 之类才能按名查到。
        // HTTP 通道依赖 routeRegistry、要懒加载，在 apiChannel() 首次创建时补注册。
        channelRegistry.register(stdinChannel);
        channelRegistry.register(approvalChannel);
        channelRegistry.register(cronChannel);
        channelRegistry.register(listenChannel);
    }

    /** 暴露注册表供 AgentRuntime 装配使用 */
    public AgentNodeRegistry registry() {
        return registry;
    }

    /**
     * 装配检查点服务。容器 refresh 之后由框架调用；
     * 为 null 时所有检查点调用退化为空操作，行为与未引入检查点一致。
     */
    public void setCheckpointService(CheckpointService checkpointService) {
        this.checkpointService = checkpointService;
    }

    public CheckpointService checkpointService() {
        return checkpointService;
    }

    // ==================== 检查点边界辅助 ====================

    /** 节点开始：写入 STARTED 记录（未开启检查点时返回 null） */
    private CheckpointRecord beginCheckpoint(AgentNodeDefinition node, AgentContext context, String fromNode) {
        CheckpointService service = checkpointService;
        return service == null ? null : service.begin(node, context, fromNode);
    }

    /** 节点成功返回：状态置 COMPLETED 并记录选中的出边（不变量 A2） */
    private void completeCheckpoint(CheckpointRecord record, AgentContext context, String selectedNext) {
        CheckpointService service = checkpointService;
        if (service != null) {
            service.complete(record, context, selectedNext);
        }
    }

    /** 节点抛异常：状态置 FAILED */
    private void failCheckpoint(CheckpointRecord record, Throwable error) {
        CheckpointService service = checkpointService;
        if (service != null) {
            service.fail(record, error);
        }
    }

    /**
     * 提交检查点并推进到下一個节点。<br>
     * 把"记录选中的出边"与"节点推进"绑在一处：迁移决策只会产生一次，
     * 检查点记录与执行流因此不会出现分歧（不变量 A2）。
     *
     * @param nextName 选中的出边；null 表示链到此结束
     * @return 下一个节点定义，nextName 为 null 时返回 null
     */
    private AgentNodeDefinition advance(AgentNodeDefinition from, CheckpointRecord record,
                                        AgentContext context, String nextName) {
        completeCheckpoint(record, context, nextName);
        return nextName == null ? null : requireNode(nextName, from);
    }

    /** 节点的默认出边（@NodeNext 的第一个）；无出边返回 null */
    private String defaultNextName(AgentNodeDefinition node) {
        String[] next = node.next();
        return (next == null || next.length == 0) ? null : next[0];
    }

    /**
     * 从指定 START 节点执行工作流。<br>
     * 多工作流模型：WorkflowScheduler 为每条工作流指定入口节点。
     */
    public <C extends AgentContext> C execute(String startNodeName, C context) {
        return execute(startNodeName, context, ConcurrencyLease.NOOP);
    }

    /**
     * 带并发租约的执行入口（{@code WorkflowScheduler} 用）。<br>
     * <br>
     * 租约让"阻塞在入口等待"的工作流把并发名额还回去，被唤醒时再取回 ——
     * 定时 / 监听这类长期驻留的工作流因此不会把并发池占死。
     *
     * @param lease 并发租约；null 视为 {@link ConcurrencyLease#NOOP}
     */
    @SuppressWarnings("unchecked")
    public <C extends AgentContext> C execute(String startNodeName, C context, ConcurrencyLease lease) {
        AgentNodeDefinition current = requireNode(startNodeName, null);
        if (current.type() != NodeType.START) {
            throw new IllegalStateException(
                    "节点 [" + startNodeName + "] 不是 START 类型，无法作为工作流入口");
        }
        // 整条工作流只初始化一次。放在这里而不是 START 节点方法里：
        // 由框架掌握时机，「初始化了几次」才不取决于节点怎么走。
        context.init();
        try {
            return doExecute(current, context, lease == null ? ConcurrencyLease.NOOP : lease);
        } finally {
            // 覆盖全部退出路径：正常到 END / ENTRANCE 会话退出 / 取消 / 抛异常。
            // PARALLEL 分支走 executeBranch，不经本方法，故不会误销毁主上下文。
            context.destroy();
        }
    }

    /**
     * 从检查点恢复执行（检查点契约 P2：跨重启恢复）。<br>
     * <br>
     * 两种情形：<br>
     * <ul>
     *     <li>{@code replay = true}：上一轮在节点中途中断 → 重跑该节点。
     *         入口节点特殊处理为"重新进入并等待外部输入"（这正是跨重启 HITL 的语义）。
     *         重跑前会把旧的未完成记录标记为放弃，避免残留导致恢复点解析歧义。</li>
     *     <li>{@code replay = false}：从已记录 {@code selectedNext} 继续。
     *         <b>不重新求值 BRANCH / condition</b>，直接沿用崩溃前的决策（不变量 A2）。</li>
     * </ul>
     *
     * @param point 由 {@code CheckpointService.resumePoint} 解析出的恢复点
     * @return 恢复执行结束时的上下文
     */
    public <C extends AgentContext> C resume(ResumePoint point) {
        return resume(point, ConcurrencyLease.NOOP);
    }

    /**
     * 带并发租约的恢复入口（{@code WorkflowScheduler} 用）。语义同
     * {@link #execute(String, AgentContext, ConcurrencyLease)}。
     */
    @SuppressWarnings("unchecked")
    public <C extends AgentContext> C resume(ResumePoint point,
                                             ConcurrencyLease lease) {
        C state = (C) point.state();
        // 快照还原出的上下文重新绑定工作流，使其重新受取消控制与检查点归属
        state.bindWorkflow(point.workflowId());

        AgentNodeDefinition node = requireNode(point.nextNode(), null);

        if (point.replay()) {
            CheckpointService service = checkpointService;
            if (service != null) {
                service.abandonUnfinished(point.workflowId(),
                        "重跑节点 " + node.name());
            }
            if (node.type() == NodeType.ENTRANCE) {
                log.info("恢复工作流 [{}]：重新进入入口节点 [{}] 等待输入",
                        point.workflowId(), node.name());
                return handleEntrance(node, state, lease == null ? ConcurrencyLease.NOOP : lease);
            }
            log.info("恢复工作流 [{}]：重跑未完成节点 [{}]（seq={}）",
                    point.workflowId(), node.name(), point.record().seq());
            return continueFrom(node, state, true);
        }

        log.info("恢复工作流 [{}]：从节点 [{}] 继续（决策取自 seq={} 的 selectedNext，不重新求值）",
                point.workflowId(), node.name(), point.record().seq());
        return continueFrom(node, state, true);
    }

    /**
     * 核心执行循环：从 current 节点出发执行。<br>
     * <br>
     * 检查点：每个节点在调用前写 STARTED、返回后写 COMPLETED（并记录选中的出边），
     * 抛异常写 FAILED。ENTRANCE 节点除外——它由各自的入口处理逻辑负责检查点，
     * 因为"阻塞等待外部输入"本身就是中断点语义。
     */
    @SuppressWarnings("unchecked")
    private <C extends AgentContext> C doExecute(AgentNodeDefinition start, C context,
                                                 ConcurrencyLease lease) {
        AgentNodeDefinition current = start;
        String previous = null;
        int iterations = 0;
        List<AgentContext> pendingBranches = null;

        while (current != null) {
            if (context.isCancelled()) {
                throw new WorkflowCancelledException(context.workflowId());
            }

            if (++iterations > maxIterations) {
                throw new IllegalStateException(
                        "Agent 节点链超过最大迭代次数 " + maxIterations
                                + "，疑似死循环，当前节点: " + current.name());
            }

            NodeType type = current.type();

            // ENTRANCE 节点：阻塞等待请求；退出时（EOF/取消）工作流结束
            if (type == NodeType.ENTRANCE) {
                return handleEntrance(current, context, lease);
            }

            CheckpointRecord ckpt = beginCheckpoint(current, context, previous);
            previous = current.name();

            // EXPORT 节点：执行方法 → 回传响应 → 继续后续节点
            if (type == NodeType.EXPORT) {
                try {
                    Object result = current.invoke(context);
                    handleExportResponse(current, result);
                } catch (Throwable t) {
                    failCheckpoint(ckpt, t);
                    String errorNext = handleNodeError(current, context, t);
                    if (errorNext == null) {
                        return context;
                    }
                    current = advance(current, ckpt, context, errorNext);
                    continue;
                }
                current = advance(current, ckpt, context, defaultNextName(current));
                continue;
            }

            // MERGE 节点
            if (type == NodeType.MERGE) {
                Object result;
                try {
                    result = current.invokeMerge(context, pendingBranches != null ? pendingBranches : List.of());
                } catch (Throwable t) {
                    failCheckpoint(ckpt, t);
                    String errorNext = handleNodeError(current, context, t);
                    if (errorNext == null) {
                        return context;
                    }
                    current = advance(current, ckpt, context, errorNext);
                    continue;
                }
                if (current.returnsContext() && result instanceof AgentContext updated) {
                    context = (C) updated;
                }
                pendingBranches = null;
                current = advance(current, ckpt, context, defaultNextName(current));
                continue;
            }

            // PARALLEL 节点
            if (type == NodeType.PARALLEL) {
                try {
                    Object result = current.invoke(context);
                    if (current.returnsContext() && result instanceof AgentContext updated) {
                        context = (C) updated;
                    }
                } catch (Throwable t) {
                    failCheckpoint(ckpt, t);
                    String errorNext = handleNodeError(current, context, t);
                    if (errorNext == null) {
                        return context;
                    }
                    current = advance(current, ckpt, context, errorNext);
                    continue;
                }
                try {
                    pendingBranches = executeParallelBranches(current, context);
                } catch (RuntimeException e) {
                    failCheckpoint(ckpt, e);
                    throw e;
                }
                // 出边记录为 MERGE 节点：分支区间不产生检查点（不变量 A6），
                // 恢复点因此落在 PARALLEL 之前的最后一个 COMPLETED 节点
                current = advance(current, ckpt, context, findMergeNode().name());
                continue;
            }

            // BRANCH 节点
            if (type == NodeType.BRANCH) {
                String nextName;
                try {
                    Object result = current.invoke(context);
                    nextName = (String) result;
                } catch (Throwable t) {
                    failCheckpoint(ckpt, t);
                    String errorNext = handleNodeError(current, context, t);
                    if (errorNext == null) {
                        return context;
                    }
                    current = advance(current, ckpt, context, errorNext);
                    continue;
                }
                if (nextName == null || !List.of(current.next()).contains(nextName)) {
                    String message = "BRANCH 节点 [" + current.name() + "] 返回 [" + nextName
                            + "] 不在 @NodeNext 声明范围内: " + List.of(current.next());
                    failCheckpoint(ckpt, new IllegalStateException(message));
                    throw new IllegalStateException(message);
                }
                current = advance(current, ckpt, context, nextName);
                continue;
            }

            // COMMAND 节点：解析命令 → 合并执行参数 → 调用节点方法
            if (type == NodeType.COMMAND) {
                try {
                    invokeCommandNode(current, context);
                } catch (Throwable t) {
                    failCheckpoint(ckpt, t);
                    String errorNext = handleNodeError(current, context, t);
                    if (errorNext == null) {
                        return context;
                    }
                    current = advance(current, ckpt, context, errorNext);
                    continue;
                }
                current = advance(current, ckpt, context, defaultNextName(current));
                continue;
            }

            // START / COMMON / END：常规执行
            try {
                Object result = current.invoke(context);
                if (current.returnsContext() && result instanceof AgentContext updated) {
                    context = (C) updated;
                }
            } catch (Throwable t) {
                failCheckpoint(ckpt, t);
                String errorNext = handleNodeError(current, context, t);
                if (errorNext == null) {
                    return context;
                }
                current = advance(current, ckpt, context, errorNext);
                continue;
            }

            if (type == NodeType.END) {
                completeCheckpoint(ckpt, context, null);
                context.afterEnd();
                return context;
            }

            current = advance(current, ckpt, context, defaultNextName(current));
        }
        return context;
    }

    /**
     * 执行一个 COMMAND 节点：解析命令 → 交给节点方法。<br>
     * <br>
     * <h2>为什么不在这里调用沙箱</h2>
     * 沙箱实例是作为<b>方法参数</b>注入的，由用户方法体决定「何时调用、是否调用、
     * 换哪个沙箱调用」（委托型语义，见 {@code @Sandbox} 的注释）。
     * 执行器只负责把 {@link CommandLine} 和 {@link Sandbox} 准备好，
     * 不去替用户决定执行策略 —— 这样「危险命令先拦截」才有地方安放。
     *
     * <h2>超时解析在这里做</h2>
     * 合并「全局配置 → 注解 → 单次参数」这条优先级链（见
     * {@link AgentNodeDefinition#resolveExecOptions}），把结果写回 SandboxRegistry
     * 供本次调用使用。集中一处保证所有沙箱行为一致。<br>
     * <br>
     * <b>注意</b>：本方法不做「命令为空」的兜底 —— 解析不到命令是<b>配置错误</b>
     * （应用没提供 {@link CommandLineResolver}，或上下文中确实没有命令），
     * 应当明确报错而非静默跳过，否则表现为「节点跑了但什么都没发生」，极难排查。
     *
     * @throws IllegalStateException 未注册命令解析器，或解析不出命令
     */
    private void invokeCommandNode(AgentNodeDefinition node, AgentContext context) {
        SandboxRegistry registry = ContextHolder.getApplication().getSandboxRegistry();

        CommandLineResolver resolver = registry.commandLineResolver()
                .orElseThrow(() -> new IllegalStateException(
                        "COMMAND 节点 [" + node.name() + "] 无法执行：未注册 CommandLineResolver。"
                                + "AgentContext 是纯抽象接口、不含命令字段，框架无法推断命令来源；"
                                + "请实现 CommandLineResolver 并注册为 Bean。"));

        CommandLine cmd = resolver.resolve(context)
                .orElseThrow(() -> new IllegalStateException(
                        "COMMAND 节点 [" + node.name() + "] 无法从上下文解析出命令（resolver="
                                + resolver.getClass().getSimpleName() + "）。"
                                + "若命令来自 LLM 的 tool_call 参数，请确认该参数已写入上下文。"));

        Object result = node.invokeCommand(context, cmd);
        log.debug("COMMAND 节点 [{}] 完成: {}", node.name(), cmd.display());
    }

    // ==================== ENTRANCE 处理 ====================
    /**
     * 处理 ENTRANCE 节点：注册路由 → 阻塞等待请求 → 处理。<br>
     * <br>
     * API 入口：主线程阻塞至工作流取消，每次请求 fork 独立线程处理。<br>
     * STDIN 入口：主线程串行处理每轮输入，一轮结束才回到本节点等待下一次输入。
     *
     * @return 退出入口节点时的上下文
     */
    @SuppressWarnings("unchecked")
    private <C extends AgentContext> C handleEntrance(AgentNodeDefinition node, C context,
                                                      ConcurrencyLease lease) {
        // 1. @ApiEntranceNode：HTTP 路由阻塞（FORK_PER_INPUT，走自己的 fork 循环）。
        //    API 禁用模式（宿主集成）下按"退化为服务入口"处理。
        ApiEntranceNode apiAnn = node.method().getAnnotation(ApiEntranceNode.class);
        if (apiAnn != null) {
            if (apiForbidden) {
                handleApiEntranceDegraded(node, context, apiAnn, lease);
            } else {
                handleApiEntrance(node, context, apiAnn, lease);
            }
            return context;
        }

        // 1.5 @ServiceEntranceNode：进程内服务入口（FORK_PER_INPUT，走自己的 fork 循环）
        ServiceEntranceNode serviceAnn = node.method().getAnnotation(ServiceEntranceNode.class);
        if (serviceAnn != null) {
            handleServiceEntrance(node, context, serviceAnn.url(), serviceAnn.concurrencyLimit(), lease);
            return context;
        }

        // 2. @ApproveNode：审批一次性等待（WAIT_ONCE，走通用循环）
        ApproveNode approveAnn = node.method().getAnnotation(ApproveNode.class);
        if (approveAnn != null) {
            // 按注解上的 channel 名查注册表 —— 这一条以前没接：注解有 channel() 属性，
            // 但这里直接用硬编码的 approvalChannel，于是"换成我自己的审批通道"根本不生效。
            EntranceChannel<?> channel = channelRegistry.get(approveAnn.channel());
            if (channel == null) {
                // 找不到就报错，不静默回退到默认通道：写了名字却没注册是配置错误，
                // 静默回退会让"我以为换成自己的了"变成最难查的那类 bug。
                throw new IllegalStateException(
                        "ENTRANCE 节点 [" + node.name() + "] 引用了未注册的入口通道 ["
                                + approveAnn.channel() + "]；已注册：" + channelRegistry.names()
                                + "。自定义通道请先 executor.channelRegistry().register(...)");
            }
            log.info("ApproveNode [{}] 已激活，等待审批（通道 {} → {}）",
                    node.name(), approveAnn.channel(), channel.getClass().getSimpleName());
            return runEntrance(node, context, channel, EntranceMode.WAIT_ONCE, null, lease);
        }

        // 3. @CronNode：定时触发（SESSION_LOOP，走通用循环；通道自带调度线程）
        CronNode cronAnn = node.method().getAnnotation(CronNode.class);
        if (cronAnn != null) {
            String[] expressions = cronAnn.cron();
            if (expressions.length == 0) {
                throw new IllegalStateException(
                        "ENTRANCE 节点 [" + node.name() + "] 的 @CronNode(cron = {...}) 为空；"
                                + "至少给出一条 cron 表达式");
            }
            String workflowId = context.workflowId();
            CronSubscription sub = cronChannel.subscribe(workflowId, expressions, cronAnn.zone());
            log.info("CronNode [{}] 已激活：cron={} zone={}（工作流 {}）",
                    node.name(), sub.expressions(), sub.zone(), workflowId);
            try {
                return runEntrance(node, context, cronChannel, EntranceMode.SESSION_LOOP, null, lease);
            } finally {
                // 退出会话（取消 / 关闭 / 异常）都要停掉调度线程，
                // 否则它会按 cron 一直算下去，而没人再来取触发。
                cronChannel.unsubscribe(workflowId);
            }
        }

        // 4. @ListenNode：外部事件监听（SESSION_LOOP；handler 注册回调后立刻返回）
        ListenNode listenAnn = node.method().getAnnotation(ListenNode.class);
        if (listenAnn != null) {
            ListenNodeHandler handler = resolveListenHandler(node, listenAnn);
            String workflowId = context.workflowId();
            listenChannel.bind(workflowId, handler, context);
            log.info("ListenNode [{}] 已激活：handler={}（工作流 {}）",
                    node.name(), handler.getClass().getSimpleName(), workflowId);
            try {
                return runEntrance(node, context, listenChannel, EntranceMode.SESSION_LOOP, null, lease);
            } finally {
                // dispose 必须在所有退出路径上发生：handler 持着外部连接，
                // 漏一次就是一个再也不会被回收的监听器。
                listenChannel.unbind(workflowId);
            }
        }

        // 5. @StdinEntranceNode：STDIN 串行会话（SESSION_LOOP，走通用循环）
        StdinEntranceNode stdinAnn = node.method().getAnnotation(StdinEntranceNode.class);
        if (stdinAnn != null) {
            log.info("StdinEntranceNode [{}] 已激活，等待输入", node.name());
            String prompt = stdinAnn.inputShow();
            stdinChannel.start();
            // 每轮循环开头打印提示符（提示符为空时不打）
            Runnable onLoopStart = (prompt != null && !prompt.isEmpty())
                    ? () -> {
                        System.out.print(prompt);
                        System.out.flush();
                    }
                    : null;
            return runEntrance(node, context, stdinChannel, EntranceMode.SESSION_LOOP, onLoopStart, lease);
        }

        // 6. 未识别的 ENTRANCE 类型：直接报错（不兜底）。
        // ENTRANCE 必须显式选择通道，框架无法替用户决定如何接收外部输入。
        throw new IllegalStateException(
                "ENTRANCE 节点 [" + node.name() + "] 未标注任何入口注解"
                        + "（@ApiEntranceNode / @ServiceEntranceNode / @StdinEntranceNode / "
                        + "@ApproveNode / @CronNode / @ListenNode）；"
                        + "请显式声明入口类型，或为新通道类型提供自定义注解与处理器。");
    }

    /**
     * 解析 {@code @ListenNode(listenOn = ...)} 的 handler 实例。<br>
     * <br>
     * <b>只从容器取，不反射 new</b>：handler 是需要依赖注入的业务类，
     * 反射 new 出来的实例 {@code @Inject} 字段全是 null（沙箱体系踩过的坑），
     * 表现为"连上了但永远收不到消息"这种最难查的失败。
     */
    private ListenNodeHandler resolveListenHandler(AgentNodeDefinition node, ListenNode ann) {
        Class<? extends ListenNodeHandler> type = ann.listenOn();
        Container c = container;
        if (c == null) {
            throw new IllegalStateException(
                    "ENTRANCE 节点 [" + node.name() + "] 的 @ListenNode 需要容器才能取 handler ["
                            + type.getName() + "]，但执行器未装配容器；"
                            + "请确认框架已完成初始化（非直接 new AgentLoopExecutor 的裸测试路径）");
        }
        ListenNodeHandler handler;
        try {
            handler = c.getBean(type);
        } catch (Throwable t) {
            throw new IllegalStateException(
                    "ENTRANCE 节点 [" + node.name() + "] 取 handler [" + type.getName()
                            + "] 失败：请给该类标 @Component 使其成为容器 Bean", t);
        }
        if (handler == null) {
            throw new IllegalStateException(
                    "ENTRANCE 节点 [" + node.name() + "] 的 handler [" + type.getName()
                            + "] 在容器中不存在；请给该类标 @Component");
        }
        return handler;
    }

    /**
     * 处理 @ApiEntranceNode：注册 HTTP 路由 → 阻塞等待请求 → fork 执行。
     */
    @SuppressWarnings("unchecked")
    private <C extends AgentContext> void handleApiEntrance(
            AgentNodeDefinition node, C context, ApiEntranceNode ann, ConcurrencyLease lease) {
        if (routeRegistry == null) {
            throw new IllegalStateException(
                    "ENTRANCE 节点 [" + node.name() + "] 需要 EntranceRouteRegistry，但未配置");
        }

        String workflowId = context.workflowId();

        // 创建 + 注册 HTTP 路由（通过 HttpEntranceChannel.createRoute 扩展方法）
        EntranceRoute route = apiChannel().createRoute(workflowId, node, ann);
        log.info("EntranceNode 路由已注册: {} → 节点 [{}]", route.fullPath(), node.name());

        // "驻留在入口等待请求"本身即中断点：写入 STARTED 记录，
        // 会话期间保持未完成，跨重启恢复时据此重新注册路由并等待。
        CheckpointRecord entranceCkpt = beginCheckpoint(node, context, null);

        // 阻塞等待请求：HttpEntranceChannel.await 内部 200ms 轮询，让取消/关闭
        // 信号能打进驻留点（原本 route.take() 阻塞且不可中断，shutdown/cancel 进不来）
        while (!context.isCancelled()) {
            EntranceRequest request;
            try {
                // 驻留等待期间把并发名额还回去：HTTP 入口在没请求时什么也不做，
                // 占着名额只会让别的工作流起不来（槽与线程解耦）。
                lease.park();
                try {
                    request = apiChannel().await(workflowId, context);
                } finally {
                    lease.unpark();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            // await 返回 null：路由被注销或通道结束（不应在正常取消路径上发生，
            // 但保险起见显式退出驻留循环）
            if (request == null) {
                log.info("HttpEntranceChannel 返回 null（路由已注销或通道结束），退出驻留循环");
                break;
            }

            if (!route.tryAcquireConcurrency()) {
                request.responseFuture().complete(EntranceResponse.error(429, "并发上限已达"));
                continue;
            }

            final EntranceRoute fRoute = route;
            final EntranceRequest fRequest = request;
            VirtualThreads.start(() -> {
                CURRENT_REQUEST.set(fRequest);
                AgentCallContext previousCallContext = AgentCallContext.bind(fRequest.callContext());
                try {
                    C forkedContext = (C) context.copy();
                    forkedContext.bindWorkflow(workflowId);

                    try {
                        node.invokeEntrance(forkedContext, fRequest.body());
                    } catch (Throwable t) {
                        log.error("ENTRANCE 节点方法执行失败: {}", node.name(), t);
                        fRequest.responseFuture().complete(
                                EntranceResponse.error(500, "节点执行失败: " + t.getMessage(), t));
                        return;
                    }

                    AgentNodeDefinition next = selectNext(node, forkedContext, null);
                    if (next != null) {
                        // 非主线程：不写检查点（不变量 A6）
                        continueFrom(next, forkedContext, false);
                    }
                } catch (Throwable t) {
                    if (t instanceof WorkflowCancelledException) {
                        fRequest.responseFuture().complete(
                                EntranceResponse.error(503, "工作流已取消", t));
                    } else {
                        log.error("fork 执行异常", t);
                        fRequest.responseFuture().complete(
                                EntranceResponse.error(500, "内部错误: " + t.getMessage(), t));
                    }
                } finally {
                    route.releaseConcurrency();
                    AgentCallContext.restore(previousCallContext);
                    CURRENT_REQUEST.remove();
                }
            });
        }

        routeRegistry.unregisterWorkflow(workflowId);
        completeCheckpoint(entranceCkpt, context, null);
        log.info("EntranceNode 路由已注销: {} (工作流已结束)", route.fullPath());
    }

    /**
     * {@code @ApiEntranceNode} 在 API 禁用模式（宿主集成）下的退化入口。<br>
     * <br>
     * 地址映射：{@code url = "agent://" + route}（去前导斜杠；route 为空时用节点名，
     * 与 HTTP 路由的默认一致）。method / headers / consumes / params 丢弃 ——
     * 进程内调用没有这些协议语义（启动期已完成"报错或退化"的校验，见 BootPolicy）。
     */
    private <C extends AgentContext> void handleApiEntranceDegraded(
            AgentNodeDefinition node, C context, ApiEntranceNode ann, ConcurrencyLease lease) {
        String route = ann.route().isEmpty() ? node.name() : ann.route();
        String url = "agent://" + route.replaceFirst("^/+", "");
        log.warn("API 入口已禁用，@ApiEntranceNode 退化为服务入口：节点 [{}] url={}；"
                        + "method/headers/consumes/params 属性被忽略（进程内调用无协议语义）",
                node.name(), url);
        handleServiceEntrance(node, context, url, ann.concurrencyLimit(), lease);
    }

    /**
     * 处理服务入口：注册服务地址 → 阻塞等待调用 → fork 执行。<br>
     * <br>
     * 与 {@link #handleApiEntrance} 同一套 fork 循环骨架，区别在：
     * <ul>
     *     <li>寻址走 {@link ServiceEntranceRegistry}（url 精确匹配，无协议校验）</li>
     *     <li>出口由 {@code @ServiceExportNode} 回传；异常经
     *         {@link EntranceResponse#cause()} 原样交给调用方（类型保真）</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private <C extends AgentContext> void handleServiceEntrance(
            AgentNodeDefinition node, C context, String url, int concurrencyLimit, ConcurrencyLease lease) {
        ServiceEntranceRegistry registry = serviceEntranceRegistry;
        ServiceEntranceChannel channel = serviceChannel;
        if (registry == null || channel == null) {
            throw new IllegalStateException(
                    "ENTRANCE 节点 [" + node.name() + "] 是服务入口，但 ServiceEntranceRegistry 未装配；"
                            + "请确认框架由 ApplicationFrameworkContext.create(..) 创建（它会装配服务入口注册表）");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                    "ENTRANCE 节点 [" + node.name() + "] 的服务地址不能为空（@ServiceEntranceNode.url 必填）");
        }

        String workflowId = context.workflowId();
        ServiceEntranceRoute route = channel.createRoute(workflowId, node, url, concurrencyLimit);
        log.info("ServiceEntrance 地址已注册: {} → 节点 [{}]（工作流 {}）", url, node.name(), workflowId);

        // 驻留等待本身即中断点：写 STARTED 记录（同 HTTP 入口，不变量 A8）
        CheckpointRecord entranceCkpt = beginCheckpoint(node, context, null);

        while (!context.isCancelled()) {
            EntranceRequest request;
            try {
                // 驻留期间把并发名额还回去（槽与线程解耦，同 HTTP 入口）
                lease.park();
                try {
                    request = channel.await(workflowId, context);
                } finally {
                    lease.unpark();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            if (request == null) {
                log.info("ServiceEntranceChannel 返回 null（地址已注销或通道结束），退出驻留循环");
                break;
            }

            if (!route.tryAcquireConcurrency()) {
                request.responseFuture().complete(EntranceResponse.error(503, "并发上限已达"));
                continue;
            }

            final ServiceEntranceRoute fRoute = route;
            final EntranceRequest fRequest = request;
            VirtualThreads.start(() -> {
                CURRENT_REQUEST.set(fRequest);
                AgentCallContext previousCallContext = AgentCallContext.bind(fRequest.callContext());
                try {
                    C forkedContext = (C) context.copy();
                    forkedContext.bindWorkflow(workflowId);

                    try {
                        node.invokeEntrance(forkedContext, fRequest.body());
                    } catch (Throwable t) {
                        // 原始异常挂到响应上：调用方按真实类型 rethrow（类型保真，不加壳）
                        log.error("服务入口节点方法执行失败: {}", node.name(), t);
                        fRequest.responseFuture().complete(
                                EntranceResponse.error(500, "节点执行失败: " + t.getMessage(), t));
                        return;
                    }

                    AgentNodeDefinition next = selectNext(node, forkedContext, null);
                    if (next != null) {
                        // 非主线程：不写检查点（不变量 A6）
                        continueFrom(next, forkedContext, false);
                    }
                } catch (Throwable t) {
                    if (t instanceof WorkflowCancelledException) {
                        fRequest.responseFuture().complete(
                                EntranceResponse.error(503, "工作流已取消", t));
                    } else {
                        log.error("服务调用执行异常", t);
                        fRequest.responseFuture().complete(
                                EntranceResponse.error(500, "内部错误: " + t.getMessage(), t));
                    }
                } finally {
                    fRoute.releaseConcurrency();
                    AgentCallContext.restore(previousCallContext);
                    CURRENT_REQUEST.remove();
                }
            });
        }

        registry.unregisterWorkflow(workflowId);
        completeCheckpoint(entranceCkpt, context, null);
        log.info("ServiceEntrance 地址已注销: {} (工作流已结束)", url);
    }

    /**
     * 懒加载获取 HTTP 入口通道。第一次有 {@code @ApiEntranceNode} 被执行时才创建，
     * 纯 STDIN 工作流不会触发任何 HTTP 相关副作用。
     */
    private HttpEntranceChannel apiChannel() {
        HttpEntranceChannel channel = apiChannel;
        if (channel == null) {
            synchronized (this) {
                channel = apiChannel;
                if (channel == null) {
                    channel = new HttpEntranceChannel(routeRegistry);
                    apiChannel = channel;
                    // 懒加载的通道也要进注册表：内置通道不特殊化，
                    // 应用想按名引用（或替换）HTTP 通道时才有一致的入口。
                    channelRegistry.register(channel);
                }
            }
        }
        return channel;
    }

    /**
     * Mode 驱动的入口节点通用循环：覆盖 {@link EntranceMode#SESSION_LOOP}（STDIN）
     * 与 {@link EntranceMode#WAIT_ONCE}（审批）两种模式。<br>
     * <br>
     * 为什么不收编 {@link EntranceMode#FORK_PER_INPUT}（HTTP）：
     * HTTP 的 fork 循环要做 bindWorkflow / 复制上下文 / 设 CURRENT_REQUEST /
     * 绑 responseFuture / 限并发 —— 这些都是 HTTP 特有的，强行参数化会让
     * 通用循环变脏。HTTP 留在自己的 {@link #handleApiEntrance} 里更干净。
     *
     * <h2>不变量</h2>
     * <ul>
     *     <li><b>A8（等待记录常驻）</b>：进入循环时立即写一条 STARTED 等待记录；
     *         循环期间通过"先开新再关旧"维持恰好一条未完成记录（避免空窗）</li>
     *     <li><b>A6（主线程写检查点）</b>：本方法只被工作流主线程调用；
     *         入口方法 invoke 与后续节点链执行都在主线程（fork 不算在内）</li>
     * </ul>
     *
     * @param onLoopStart 每轮循环开头执行的回调（STDIN 用于打印提示符）；
     *                    null = 不调用。WAIT_ONCE 模式忽略
     */
    @SuppressWarnings("unchecked")
    private <I, C extends AgentContext> C runEntrance(
            AgentNodeDefinition node, C context,
            EntranceChannel<I> channel, EntranceMode mode,
            Runnable onLoopStart, ConcurrencyLease lease) {

        // 「阻塞等待输入」本身就是中断点：写入 STARTED 记录，负载为当前累积状态。
        // 会话运行期间始终保留一条未完成记录，跨重启恢复时据此重新进入本节点等待输入
        // （检查点契约 A1：STARTED 未完成 = 执行中崩溃 或 正在等待外部输入）。
        CheckpointRecord waiting = beginCheckpoint(node, context, null);

        if (mode == EntranceMode.WAIT_ONCE) {
            // 一次性：阻塞取一次 → invoke → 跑后续到 END → 关闭等待记录 → 返回
            return runEntranceOnce(node, context, channel, waiting, lease);
        }

        // SESSION_LOOP：每轮（取输入 → 跑节点链到 END）循环
        while (!context.isCancelled()) {
            // 每轮开头：可选用回调（STDIN 用来打印提示符）
            if (onLoopStart != null) {
                onLoopStart.run();
            }

            // 阻塞取一次输入（200ms 轮询保证取消/关闭信号能打进来）。
            // 等待期间把并发名额还回去 —— 定时/监听这类长期驻留的入口，
            // 若一直占着名额，几个就能把并发池堵死（槽与线程解耦）。
            I input;
            try {
                lease.park();
                try {
                    input = channel.await(context.workflowId(), context);
                } finally {
                    lease.unpark();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            if (context.isCancelled()) {
                break;
            }
            if (input == null) {
                // 通道已结束（EOF / 关闭）：会话退出
                log.info("ENTRANCE [{}] 通道已结束，退出循环", node.name());
                break;
            }

            // 同步执行本轮：调用入口方法 → 从下一节点执行到 END
            try {
                String body = channel.toBody(input);
                node.invokeEntrance(context, body, channel.isJsonBody());

                AgentNodeDefinition next = selectNext(node, context, null);
                if (next != null) {
                    // 主执行线程：本轮节点链写检查点（不变量 A6 的反面）
                    context = continueFrom(next, context, true);
                }
            } catch (WorkflowCancelledException e) {
                break;  // 工作流取消：结束会话（等待记录在循环外统一关闭）
            } catch (Throwable t) {
                // 单轮失败不终止会话，记录后继续等待下一次输入
                log.error("ENTRANCE [{}] 本轮执行异常", node.name(), t);
            }

            // 本轮结束，回到等待状态。顺序很重要：先写新的等待记录（负载为最新状态），
            // 再关闭本轮记录 —— 保证任意时刻都恰好有一条未完成记录，
            // 恢复点因此不会出现"空窗"（若先关闭再新建，中间崩溃会让工作流看起来已结束）。
            CheckpointRecord nextWaiting = beginCheckpoint(node, context, node.name());
            completeCheckpoint(waiting, context, null);
            waiting = nextWaiting;
        }

        // 会话结束（取消 / 关闭 / EOF）：关闭等待记录，工作流不再有未完成检查点，
        // 恢复点解析据此判定该工作流已结束（而非"正在等待输入"）。
        completeCheckpoint(waiting, context, null);
        log.info("ENTRANCE [{}] 已退出", node.name());
        return context;
    }

    /**
     * 一次性等待模式：阻塞取一次输入 → invoke 入口方法 → 跑后续到 END → 返回。<br>
     * <br>
     * 与 SESSION_LOOP 的差别：只取一次，不循环。WAIT_ONCE 的典型场景是人工审批。
     */
    @SuppressWarnings("unchecked")
    private <I, C extends AgentContext> C runEntranceOnce(
            AgentNodeDefinition node, C context,
            EntranceChannel<I> channel, CheckpointRecord waiting, ConcurrencyLease lease) {

        I input;
        try {
            lease.park();
            try {
                input = channel.await(context.workflowId(), context);
            } finally {
                lease.unpark();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            completeCheckpoint(waiting, context, null);
            return context;
        }

        if (input == null) {
            // 通道已结束（关闭 / 取消）：本节点视为未取到审批，不执行后续
            log.info("ENTRANCE [{}] 等待输入失败（通道已结束）", node.name());
            completeCheckpoint(waiting, context, null);
            return context;
        }

        try {
            String body = channel.toBody(input);
            node.invokeEntrance(context, body, channel.isJsonBody());

            AgentNodeDefinition next = selectNext(node, context, null);
            if (next != null) {
                context = continueFrom(next, context, true);
            }
        } catch (WorkflowCancelledException e) {
            // 工作流取消：等待记录在下面统一关闭
        } catch (Throwable t) {
            log.error("ENTRANCE [{}] 执行异常", node.name(), t);
        }

        completeCheckpoint(waiting, context, null);
        log.info("ENTRANCE [{}] 已退出（一次性）", node.name());
        return context;
    }

    /**
     * 唤醒所有阻塞在 STDIN 等待上的会话，并拒绝后续输入。<br>
     * 供 {@link WorkflowScheduler} 在
     * shutdownNow 时调用。内部转发给 STDIN 通道。<br>
     * 注意：{@code readLine()} 不响应 interrupt，所以这里不依赖中断，只依赖哨兵与关闭标记。
     */
    public void wakeupStdin() {
        stdinChannel.close();
    }

    /** STDIN 是否已进入关闭状态 */
    public boolean isStdinClosing() {
        return stdinChannel.isClosing();
    }

    /**
     * 从指定节点继续执行（入口节点处理每轮输入时调用）。<br>
     * 返回执行结束时的上下文，供入口节点串行会话循环沿用最新状态。
     *
     * @param trackCheckpoint 是否写入检查点。仅工作流<b>主执行线程</b>应传 true；
     *                        HTTP 请求 fork 传 false（不变量 A6：非主线程不产生检查点，
     *                        否则请求级记录会污染工作流的恢复点）
     */
    @SuppressWarnings("unchecked")
    private <C extends AgentContext> C continueFrom(AgentNodeDefinition start, C context,
                                                    boolean trackCheckpoint) {
        AgentNodeDefinition current = start;
        String previous = null;
        int iterations = 0;
        // PARALLEL 产出的分支上下文，交给下一个 MERGE 消费（与 execute() 主循环同构）
        List<AgentContext> pendingBranches = null;

        while (current != null) {
            if (context.isCancelled()) {
                throw new WorkflowCancelledException(context.workflowId());
            }

            if (++iterations > maxIterations) {
                throw new IllegalStateException(
                        "节点链执行超过最大迭代次数: " + current.name());
            }

            NodeType type = current.type();

            CheckpointRecord ckpt = trackCheckpoint
                    ? beginCheckpoint(current, context, previous)
                    : null;
            previous = current.name();

            // EXPORT：执行方法 → 回传响应 → 继续
            if (type == NodeType.EXPORT) {
                try {
                    Object result = current.invoke(context);
                    handleExportResponse(current, result);
                } catch (Throwable t) {
                    failCheckpoint(ckpt, t);
                    String errorNext = handleNodeError(current, context, t);
                    if (errorNext == null) {
                        return context;
                    }
                    current = advance(current, ckpt, context, errorNext);
                    continue;
                }
                current = advance(current, ckpt, context, defaultNextName(current));
                continue;
            }

            // MERGE 节点
            // ⚠ 这一段（以及下面的 PARALLEL）是补上的：continueFrom 原本只处理
            // EXPORT / ENTRANCE / END / BRANCH / COMMAND / 常规，PARALLEL 与 MERGE 只在
            // execute() 主循环里有分支。而 SESSION_LOOP 每一轮实际走的是 continueFrom ——
            // 于是并行段落会退化成「普通节点 invoke 一次」，分支不执行、MERGE 收不到东西，
            // 全程不报错。补上与主循环一致的处理。
            if (type == NodeType.MERGE) {
                Object result;
                try {
                    result = current.invokeMerge(context, pendingBranches != null ? pendingBranches : List.of());
                } catch (Throwable t) {
                    failCheckpoint(ckpt, t);
                    String errorNext = handleNodeError(current, context, t);
                    if (errorNext == null) {
                        return context;
                    }
                    current = advance(current, ckpt, context, errorNext);
                    continue;
                }
                if (current.returnsContext() && result instanceof AgentContext updated) {
                    context = (C) updated;
                }
                pendingBranches = null;
                current = advance(current, ckpt, context, defaultNextName(current));
                continue;
            }

            // PARALLEL 节点
            if (type == NodeType.PARALLEL) {
                try {
                    Object result = current.invoke(context);
                    if (current.returnsContext() && result instanceof AgentContext updated) {
                        context = (C) updated;
                    }
                } catch (Throwable t) {
                    failCheckpoint(ckpt, t);
                    String errorNext = handleNodeError(current, context, t);
                    if (errorNext == null) {
                        return context;
                    }
                    current = advance(current, ckpt, context, errorNext);
                    continue;
                }
                try {
                    pendingBranches = executeParallelBranches(current, context);
                } catch (RuntimeException e) {
                    failCheckpoint(ckpt, e);
                    throw e;
                }
                // 出边记录为 MERGE 节点：分支区间不产生检查点（不变量 A6）
                current = advance(current, ckpt, context, findMergeNode().name());
                continue;
            }

            // ENTRANCE 不应嵌套出现（设计原则：ENTRANCE 是工作流入口节点，
            // 不应在工作流中段再次遇到）。直接报错，不交给 @NodeExceptionAdvice 兜底
            // ——这是设计错误，不是节点执行异常。
            if (type == NodeType.ENTRANCE) {
                throw new IllegalStateException(
                        "工作流执行过程中遇到嵌套 ENTRANCE 节点 [" + current.name()
                                + "]；ENTRANCE 仅可作为工作流入口，不可放在其他节点之后。"
                                + "请将该节点改为 COMMON，或把它拆为独立工作流。");
            }

            // END：结束
            if (type == NodeType.END) {
                try {
                    current.invoke(context);
                } catch (Throwable t) {
                    // END 方法本身失败：不算走完一轮，不触发 afterEnd；
                    // 资源释放交给 execute() 的 destroy
                    failCheckpoint(ckpt, t);
                    handleNodeError(current, context, t);
                    return context;
                }
                completeCheckpoint(ckpt, context, null);
                // 会话型工作流（SESSION_LOOP）每轮都走到这里 —— 本轮善后
                context.afterEnd();
                return context;
            }

            // BRANCH
            if (type == NodeType.BRANCH) {
                String nextName;
                try {
                    Object result = current.invoke(context);
                    nextName = (String) result;
                } catch (Throwable t) {
                    failCheckpoint(ckpt, t);
                    String errorNext = handleNodeError(current, context, t);
                    if (errorNext == null) return context;
                    current = advance(current, ckpt, context, errorNext);
                    continue;
                }
                if (nextName == null || !List.of(current.next()).contains(nextName)) {
                    String message = "BRANCH 节点 [" + current.name() + "] 返回 [" + nextName
                            + "] 不在 @NodeNext 范围内";
                    failCheckpoint(ckpt, new IllegalStateException(message));
                    throw new IllegalStateException(message);
                }
                current = advance(current, ckpt, context, nextName);
                continue;
            }

            // COMMAND 节点：解析命令 → 调用节点方法
            // 必须在此分支处理：若掉进下面的「常规执行」，CommandLine 形参会走通用
            // 参数绑定、在容器里找不到对应 Bean 而**静默注入 null** ——
            // 用户方法拿到 null 命令，表现为「节点跑了但什么都没发生」，极难排查。
            // （continueFrom 是 SESSION_LOOP 每轮实际走的路径，此处遗漏代价更大。）
            if (type == NodeType.COMMAND) {
                try {
                    invokeCommandNode(current, context);
                } catch (Throwable t) {
                    failCheckpoint(ckpt, t);
                    String errorNext = handleNodeError(current, context, t);
                    if (errorNext == null) return context;
                    current = advance(current, ckpt, context, errorNext);
                    continue;
                }
                current = advance(current, ckpt, context, defaultNextName(current));
                continue;
            }

            // COMMON / START / TOOL：常规执行
            // TOOL 无需专属分支 —— AgentToolInvoker 由通用参数绑定从容器注入
            try {
                Object result = current.invoke(context);
                if (current.returnsContext() && result instanceof AgentContext updated) {
                    context = (C) updated;
                }
            } catch (Throwable t) {
                failCheckpoint(ckpt, t);
                String errorNext = handleNodeError(current, context, t);
                if (errorNext == null) return context;
                current = advance(current, ckpt, context, errorNext);
                continue;
            }

            current = advance(current, ckpt, context, defaultNextName(current));
        }

        return context;
    }

    // ==================== EXPORT 响应回传 ====================

    /**
     * 处理 EXPORT 节点的响应回传。<br>
     * 读取 @ApiExportNode 注解，将方法返回值序列化为 JSON 响应。
     */
    private void handleExportResponse(AgentNodeDefinition node, Object result) {
        ApiExportNode apiAnn = node.method().getAnnotation(ApiExportNode.class);
        ServiceExportNode serviceAnn = node.method().getAnnotation(ServiceExportNode.class);
        if (apiAnn == null && serviceAnn == null) {
            // 非出口注解（普通 EXPORT 或 STDIN EXPORT），不回传响应
            return;
        }
        // 服务出口没有状态码概念；HTTP 出口取注解声明的状态码（默认 200）
        int status = apiAnn != null ? apiAnn.status() : 200;

        EntranceRequest request = CURRENT_REQUEST.get();
        if (request == null) {
            log.debug("EXPORT 节点 [{}] 无关联请求（非 fork 执行或已回传）", node.name());
            return;
        }

        // 构造响应
        EntranceResponse response;
        if (result instanceof EntranceResponse custom) {
            // 节点方法自己返回了 EntranceResponse —— 以它为准。<br>
            // @ApiExportNode.status 是注解常量（编译期写死），没法表达
            //「受理成功 / 参数校验失败 / 下游不可用」这类<b>按结果变化</b>的状态码。
            // 需要动态状态时直接 {@code return EntranceResponse.of(422, json);}。
            response = custom;
        } else if (result == null || node.method().getReturnType() == void.class) {
            response = EntranceResponse.noContent();
        } else if (result instanceof String s) {
            response = new EntranceResponse(status, s, "application/json; charset=UTF-8");
        } else {
            try {
                String json = JsonUtil.toJson(result);
                response = new EntranceResponse(status, json, "application/json; charset=UTF-8");
            } catch (Exception e) {
                response = EntranceResponse.error(500, "响应序列化失败: " + e.getMessage(), e);
            }
        }

        // complete() 返回 false = 这个 Future 已经作废（客户端等待超时，ApiHttpServer 已 cancel）。
        // 以前忽略返回值：结果被塞进一个无人接收的 Future，全程零日志 —— 看上去就是"白跑"。
        boolean delivered = request.responseFuture().complete(response);
        if (!delivered) {
            // 只记日志，不尝试取消工作流：fork 副本与常驻本体共享 workflowId，
            // 按 workflowId 取消会连带误杀入口本体（当前框架没有"单次 fork"的取消粒度）。
            // 长任务想止损，应在节点内部自行判断请求是否已放弃。
            log.warn("EXPORT [{}] 的响应已作废（客户端等待超时），本次结果丢弃；"
                            + "工作流继续运行 —— 长任务应在节点内检查请求状态提前退出",
                    node.name());
        }
        // 清除关联，防止后续 EXPORT 节点重复回传
        CURRENT_REQUEST.remove();
    }

    // ==================== 并行分支 ====================

    @SuppressWarnings("unchecked")
    private <C extends AgentContext> List<AgentContext> executeParallelBranches(
            AgentNodeDefinition parallelNode, C context) {
        String[] branchStarts = parallelNode.next();
        List<CompletableFuture<AgentContext>> futures = new ArrayList<>();

        for (String branchStart : branchStarts) {
            AgentNodeDefinition startNode = requireNode(branchStart, parallelNode);
            AgentContext branchContext = context.copy();
            futures.add(CompletableFuture.supplyAsync(() -> {
                log.debug("并行分支启动: {} -> {}", parallelNode.name(), branchStart);
                return executeBranch(startNode, (C) branchContext);
            }, parallelExecutor));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        List<AgentContext> results = new ArrayList<>();
        for (CompletableFuture<AgentContext> f : futures) {
            results.add(f.join());
        }
        log.debug("并行分支全部完成，共 {} 个分支", results.size());
        return results;
    }

    @SuppressWarnings("unchecked")
    private <C extends AgentContext> C executeBranch(AgentNodeDefinition startNode, C context) {
        AgentNodeDefinition current = startNode;
        while (current != null && current.type() != NodeType.MERGE) {
            // 分支里的 END 不触发 afterEnd：此处 context 是 copy() 出来的游离副本
            // （不归属工作流、不受取消控制），没有「一轮会话」语义，
            // 且副本在 MERGE 后即丢弃。若在此触发，用户写在 afterEnd 里的
            // 落盘 / 上报逻辑会被每个分支重复执行一遍。
            if (current.type() == NodeType.END) {
                return context;
            }
            Object result;
            try {
                result = current.invoke(context);
                if (current.returnsContext() && result instanceof AgentContext updated) {
                    context = (C) updated;
                }
            } catch (Throwable t) {
                String errorNext = handleNodeError(current, context, t);
                if (errorNext == null) {
                    return context;
                }
                current = requireNode(errorNext, current);
                continue;
            }

            if (current.type() == NodeType.BRANCH) {
                // ⚠ 旧实现在这里<b>又 invoke 了一次</b> —— 上面刚调用过，于是带副作用的
                // 分支方法会跑两遍（写库、计数、发消息这类副作用直接翻倍）。
                // 分支名就是上一次调用的返回值，直接复用。
                String nextName = (String) result;
                if (!List.of(current.next()).contains(nextName)) {
                    throw new IllegalStateException(
                            "BRANCH 节点 [" + current.name() + "] 返回 [" + nextName
                                    + "] 不在 @NodeNext 范围内");
                }
                current = requireNode(nextName, current);
            } else {
                current = selectNext(current, context, null);
            }
        }
        return context;
    }

    // ==================== 工具方法 ====================

    private AgentNodeDefinition findMergeNode() {
        java.util.List<AgentNodeDefinition> merges =
                new java.util.ArrayList<>(registry.byType(NodeType.MERGE));
        if (merges.isEmpty()) {
            throw new IllegalStateException("PARALLEL 节点执行后未找到 MERGE 汇合节点");
        }
        if (merges.size() > 1) {
            // 旧实现 findFirst() 之后什么都不说：图里有多个 MERGE 时只有第一个会执行，
            // 其余静默不跑。至少让它可见。
            log.warn("工作流里有 {} 个 MERGE 节点 {}，PARALLEL 只会汇合到 [{}]；"
                            + "其余 MERGE 不会被执行 —— 当前版本一张工作流图只支持一个 MERGE",
                    merges.size(),
                    merges.stream().map(AgentNodeDefinition::name).toList(),
                    merges.get(0).name());
        }
        return merges.get(0);
    }

    private String handleNodeError(AgentNodeDefinition node, Object context, Throwable error) {
        if (adviceRegistry == null) {
            throwRuntime(error);
            return null;
        }
        NodeExceptionHandlerDefinition handler =
                adviceRegistry.resolve(node.target().getClass(), error);
        if (handler == null) {
            throwRuntime(error);
            return null;
        }
        return handler.handle(error, context);
    }

    private void throwRuntime(Throwable error) {
        if (error instanceof RuntimeException re) {
            throw re;
        }
        throw new IllegalStateException("节点执行失败: " + error.getMessage(), error);
    }

    @SuppressWarnings("unchecked")
    private <C extends AgentContext> AgentNodeDefinition selectNext(
            AgentNodeDefinition node, C context, String override) {
        String[] next = node.next();
        if (next == null || next.length == 0) {
            return null;
        }
        if (override != null) {
            return requireNode(override, node);
        }
        return requireNode(next[0], node);
    }

    private AgentNodeDefinition requireNode(String name, AgentNodeDefinition from) {
        return registry.get(name).orElseThrow(() -> new IllegalStateException(
                "节点 [" + (from != null ? from.name() : "ROOT")
                        + "] 的 @NodeNext 指向不存在的节点: [" + name + "]"));
    }
}
