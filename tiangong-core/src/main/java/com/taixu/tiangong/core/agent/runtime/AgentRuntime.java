package com.taixu.tiangong.core.agent.runtime;

import com.taixu.tiangong.common.exception.CheckpointException;
import lombok.extern.slf4j.Slf4j;
import com.taixu.tiangong.annotations.annotation.framework.agent.interfaces.StartCondition;
import com.taixu.tiangong.annotations.annotation.framework.agent.TriggerPolicy;
import com.taixu.tiangong.annotations.annotation.framework.agent.interfaces.TriggerPolicyType;
import com.taixu.tiangong.annotations.annotation.framework.agent.interfaces.TriggerStrategy;
import com.taixu.tiangong.common.agent.context.AgentContext;
import com.taixu.tiangong.core.agent.AgentLoopExecutor;
import com.taixu.tiangong.core.agent.AgentNodeDefinition;
import com.taixu.tiangong.core.agent.AgentNodeRegistry;
import com.taixu.tiangong.core.checkpoint.CheckpointRecord;
import com.taixu.tiangong.core.checkpoint.CheckpointService;
import com.taixu.tiangong.core.checkpoint.ResumePoint;
import com.taixu.tiangong.core.container.Container;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

/**
 * AgentRuntime：框架常驻运行时，管理多工作流的触发、调度、执行。<br>
 * <br>
 * 架构层次：
 * <pre>
 * AgentRuntime（顶层常驻）
 *   ├── GlobalVariableStore        全局变量存储（信号总线）
 *   ├── StartTriggerSource         触发源（轮询 + 事件驱动混合）
 *   ├── WorkflowScheduler          工作流调度器（并发管理、生命周期）
 *   ├── AgentLoopExecutor          单工作流执行器（不变）
 *   └── ResourceOwnershipRegistry  资源占用检测
 * </pre>
 * <br>
 * 生命周期：框架启动时 {@link #start()}（阻塞主线程），手动关闭时 {@link #shutdown()}。<br>
 * 工作流结束不影响框架，仅手动 shutdown 终止。
 */
@Slf4j
public class AgentRuntime {

    /** 默认最大并发工作流数 */
    public static final int DEFAULT_MAX_CONCURRENT_WORKFLOWS = 16;

    private final AgentNodeRegistry registry;
    private final AgentLoopExecutor executor;
    private final Container container;
    private final Class<? extends AgentContext> contextType;
    private final int maxConcurrentWorkflows;

    private GlobalVariableStore globalVariableStore;
    private ResourceOwnershipRegistry resourceOwnership;
    private WorkflowScheduler scheduler;
    private StartTriggerSource triggerSource;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    /** 关机 hook 执行完毕的信号，供日志 flush hook 排序（见 {@link #registerShutdownHooks()}） */
    private final CountDownLatch shutdownHookDone = new CountDownLatch(1);

    /**
     * JVM 关机钩子开关：单机 true（默认）；嵌入宿主（Spring 集成）时由宿主的销毁回调
     * 调用 {@link #shutdown()}，置 false 避免重复注册与双重收尾。
     */
    private volatile boolean jvmShutdownHooks = true;

    /**
     * 设置是否注册 JVM 关机钩子。必须在 {@link #start()} 之前调用。
     */
    public void setJvmShutdownHooks(boolean enabled) {
        this.jvmShutdownHooks = enabled;
    }

    /** 关闭日志上下文前留给 AsyncAppender 队列排空的时间 */
    private static final long LOG_FLUSH_MILLIS = 200L;

    public AgentRuntime(AgentNodeRegistry registry,
                        AgentLoopExecutor executor,
                        Container container,
                        Class<? extends AgentContext> contextType) {
        this(registry, executor, container, contextType, DEFAULT_MAX_CONCURRENT_WORKFLOWS);
    }

    public AgentRuntime(AgentNodeRegistry registry,
                        AgentLoopExecutor executor,
                        Container container,
                        Class<? extends AgentContext> contextType,
                        int maxConcurrentWorkflows) {
        this.registry = registry;
        this.executor = executor;
        this.container = container;
        this.contextType = contextType;
        this.maxConcurrentWorkflows = maxConcurrentWorkflows;
    }

    /**
     * 启动运行时：装配所有组件，注册 START 节点，开始触发监听。<br>
     * <b>阻塞主线程</b>，直到 {@link #shutdown()} 被调用。
     */
    public void start() {
        // 0. 工作流图合法性校验（启动期，不被异常注解捕获）
        WorkflowGraphValidator.validate(registry);

        // 1. 创建全局变量存储（单例模式）
        this.globalVariableStore = new SingletonGlobalVariableStore();

        // 2. 创建资源占用注册表
        this.resourceOwnership = new ResourceOwnershipRegistry();

        // 3. 创建工作流调度器
        this.scheduler = new WorkflowScheduler(
                registry, executor, container, contextType,
                resourceOwnership, maxConcurrentWorkflows);

        // 4. 注册所有 START 节点 + 装配触发策略
        for (AgentNodeDefinition node : registry.startNodes()) {
            scheduler.registerStartNode(node.name());
            resolveTriggerPolicy(node);
        }

        // 5. 创建触发源并注册
        this.triggerSource = new StartTriggerSource(registry, container, scheduler);
        triggerSource.registerAll();

        // 6. 注册 shutdown hook（JVM 退出时优雅关闭）
        registerShutdownHooks();

        log.info("AgentRuntime 启动完成：{} 个 START 节点，最大并发 {}",
                registry.startNodes().size(), maxConcurrentWorkflows);

        // 7. 阻塞主线程，直到 shutdown
        try {
            shutdownLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("AgentRuntime 主线程被中断，准备关闭");
            shutdown();
        }
    }

    /**
     * 解析 START 节点的触发策略注解，装配到 scheduler。
     */
    private void resolveTriggerPolicy(AgentNodeDefinition startNode) {
        Class<? extends StartCondition> condClass = startNode.startConditionClass();
        if (condClass == null || condClass == StartCondition.class) {
            scheduler.setTriggerPolicy(startNode.name(), TriggerPolicyType.REJECT, null);
            return;
        }

        TriggerPolicy policy = condClass.getAnnotation(TriggerPolicy.class);
        if (policy == null) {
            scheduler.setTriggerPolicy(startNode.name(), TriggerPolicyType.REJECT, null);
            return;
        }

        TriggerStrategy customStrategy = null;
        if (policy.value() == TriggerPolicyType.CUSTOM && policy.strategy() != TriggerStrategy.class) {
            try {
                customStrategy = policy.strategy().getConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "无法实例化自定义触发策略 [" + policy.strategy().getName() + "]", e);
            }
        }
        scheduler.setTriggerPolicy(startNode.name(), policy.value(), customStrategy);
    }

    /**
     * 查询所有活跃工作流。
     */
    public List<WorkflowInstance> listActiveWorkflows() {
        return scheduler.listActiveWorkflows();
    }

    /**
     * 取消指定工作流（协作式中断）。
     */
    public boolean kill(String workflowId) {
        return scheduler.kill(workflowId);
    }

    /**
     * 获取全局变量存储（信号总线）。
     */
    public GlobalVariableStore globalVariableStore() {
        return globalVariableStore;
    }

    /**
     * 获取资源占用注册表。
     */
    public ResourceOwnershipRegistry resourceOwnership() {
        return resourceOwnership;
    }

    /**
     * 获取工作流调度器。
     */
    public WorkflowScheduler scheduler() {
        return scheduler;
    }

    // ==================== 检查点（P2：恢复与时间旅行） ====================

    /**
     * 检查点服务；未开启 {@code @EnableCheckpoint} 时返回 null。
     */
    public CheckpointService checkpointService() {
        return executor.checkpointService();
    }

    /** 检查点功能是否已启用 */
    public boolean checkpointEnabled() {
        CheckpointService service = executor.checkpointService();
        return service != null && service.isEnabled();
    }

    /**
     * 列出所有存在检查点的工作流 ID。<br>
     * 跨重启启动时用它枚举"可能需要恢复的工作流"。
     */
    public List<String> listCheckpointedWorkflows() {
        return requireCheckpointService().workflowIds();
    }

    /**
     * 解析指定工作流的恢复点；无恢复点（已结束 / 无记录）时返回 empty。
     *
     * @throws CheckpointException
     *         未完成节点声明了 {@code ReplayPolicy.NEVER}，或存在多条未完成记录
     */
    public Optional<ResumePoint> resumePoint(String workflowId) {
        return requireCheckpointService().resumePoint(workflowId);
    }

    /**
     * 时间旅行：按 seq 升序返回某工作流的全部检查点记录（只读回看）。<br>
     * 不产生新记录，也不影响运行中的工作流。
     */
    public List<CheckpointRecord> checkpointHistory(String workflowId) {
        return requireCheckpointService().history(workflowId);
    }

    /**
     * 跨重启恢复：解析恢复点并交回调度器执行。<br>
     * 恢复执行与正常启动共用并发账务（占用全局许可、计入该 START 的活跃数）。
     *
     * @return true 表示已受理并开始恢复；无恢复点或该工作流已在运行时返回 false
     */
    public boolean resume(String workflowId) {
        CheckpointService service = requireCheckpointService();

        if (!service.durable()) {
            log.warn("检查点存储不具备跨进程存活能力，恢复仅在本进程内有效（workflowId={}）",
                    workflowId);
        }

        Optional<ResumePoint> point = service.resumePoint(workflowId);
        if (point.isEmpty()) {
            log.info("工作流 [{}] 无恢复点（无检查点记录，或已正常走到 END），无需恢复", workflowId);
            return false;
        }

        String startNode = service.startNodeOf(workflowId).orElseThrow(() ->
                new IllegalStateException("工作流 [" + workflowId + "] 有恢复点但无法定位起始节点"));

        log.info("请求恢复工作流 [{}]：start={}，{} 节点 [{}]",
                workflowId, startNode,
                point.get().replay() ? "重跑" : "继续", point.get().nextNode());
        return scheduler.resume(startNode, point.get());
    }

    private CheckpointService requireCheckpointService() {
        CheckpointService service = executor.checkpointService();
        if (service == null) {
            throw new IllegalStateException(
                    "检查点功能未启用：请在启动类上标注 @EnableCheckpoint");
        }
        return service;
    }

    /**
     * 注册关机 hook。<br>
     * <br>
     * 两个 hook 有严格先后关系，靠 {@link #shutdownHookDone} 排序：<br>
     * <ol>
     *     <li>{@code agent-runtime-shutdown}：跑 {@link #shutdown()} 的清理（等/取消工作流）</li>
     *     <li>{@code agent-runtime-log-flush}：等第 1 个 hook 真正跑完，再 flush 并关闭日志上下文</li>
     * </ol>
     * JVM 的多个 hook 是并发执行的，顺序不保证，所以不能依赖注册顺序。之所以要这样排，
     * 是因为 logback 的 {@code DelayingShutdownHook}（固定 delay 后 context.stop()）会
     * 在关机中途把日志系统关掉，导致关机结论日志（例如"仍有 N 个工作流未退出"）被静默吞掉——
     * 这也是"关机日志突然断掉"的真正原因。现在由框架自己掌握"日志何时可以停"。<br>
     * <br>
     * <b>嵌入宿主（Spring）时关闭</b>：宿主接管生命周期（{@code BootPolicy.jvmShutdownHooks=false}），
     * 由宿主的销毁回调调用 {@link #shutdown()}，不重复注册 JVM 钩子。
     */
    private void registerShutdownHooks() {
        if (!jvmShutdownHooks) {
            log.debug("JVM 关机钩子已关闭（由宿主接管生命周期）");
            return;
        }
        Thread runtimeHook = new Thread(() -> {
            try {
                shutdown();
            } finally {
                shutdownHookDone.countDown();
            }
        }, "agent-runtime-shutdown");
        Runtime.getRuntime().addShutdownHook(runtimeHook);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                shutdownHookDone.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            flushAndCloseLogging();
        }, "agent-runtime-log-flush"));
    }

    /**
     * 关闭日志上下文：等待异步 appender 队列排空后 stop()。<br>
     * 只在 JVM 关机（由 {@link #registerShutdownHooks()} 注册的 hook 触发）时执行；
     * {@link #shutdown()} 被程序主动调用时不会碰日志系统。
     */
    private static void flushAndCloseLogging() {
        try {
            Thread.sleep(LOG_FLUSH_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (org.slf4j.LoggerFactory.getILoggerFactory()
                    instanceof ch.qos.logback.classic.LoggerContext ctx) {
                ctx.stop();
            }
        } catch (Throwable ignored) {
            // 日志实现不是 logback 或类缺失：交给 JVM 自行处理
        }
    }

    /**
     * 优雅关闭：两段式（自然结束 → 协作式取消 → 汇报残留），关闭触发源。<br>
     * 不再盲等固定时长：阻塞等待输入的工作流不会自己结束，盲等只会白白拖住关机。
     */
    public void shutdown() {
        log.info("AgentRuntime 正常关闭");
        if (triggerSource != null) triggerSource.shutdown();
        if (scheduler != null) scheduler.shutdown();
        shutdownLatch.countDown();
    }

    /**
     * 强制关闭：中断所有工作流。
     */
    public void shutdownNow() {
        log.info("AgentRuntime 强制关闭");
        if (triggerSource != null) triggerSource.shutdown();
        if (scheduler != null) scheduler.shutdownNow();
        shutdownLatch.countDown();
    }
}
