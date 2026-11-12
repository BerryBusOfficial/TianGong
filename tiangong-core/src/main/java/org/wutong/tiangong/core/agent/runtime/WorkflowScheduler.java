package org.wutong.tiangong.core.agent.runtime;

import lombok.extern.slf4j.Slf4j;
import org.wutong.tiangong.annotations.annotation.definition.Lifecycle;
import org.wutong.tiangong.annotations.annotation.framework.Scope;
import org.wutong.tiangong.annotations.annotation.framework.agent.interfaces.TriggerContext;
import org.wutong.tiangong.annotations.annotation.framework.agent.TriggerPolicy;
import org.wutong.tiangong.annotations.annotation.framework.agent.interfaces.TriggerPolicyType;
import org.wutong.tiangong.annotations.annotation.framework.agent.interfaces.TriggerStrategy;
import org.wutong.tiangong.common.agent.context.AgentContext;
import org.wutong.tiangong.common.enums.Namespace;
import org.wutong.tiangong.common.enums.ResourceType;
import org.wutong.tiangong.common.thread.VirtualThreads;
import org.wutong.tiangong.common.util.IdGenerator;
import org.wutong.tiangong.core.agent.AgentLoopExecutor;
import org.wutong.tiangong.core.agent.AgentNodeRegistry;
import org.wutong.tiangong.core.agent.ConcurrencyLease;
import org.wutong.tiangong.core.checkpoint.ResumePoint;
import org.wutong.tiangong.core.container.Container;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 工作流调度器：管理多条工作流的并发执行、生命周期、多触发策略。<br>
 * <br>
 * 职责：
 * <ul>
 *     <li>接收 {@link #startWorkflow} 请求，按 {@link TriggerPolicy} 决定是否启动</li>
 *     <li>为每条工作流分配虚拟线程执行 {@link AgentLoopExecutor#execute}</li>
 *     <li>全局并发上限（Semaphore）+ 每 START 节点队列/状态管理</li>
 *     <li>工作流结束/异常时清理资源、释放占用</li>
 *     <li>查询/取消运行中的工作流</li>
 * </ul>
 * <br>
 * 线程模型：工作流在虚拟线程中执行；平台线程池仅用于 CPU 密集委托。<br>
 * 限流：工作流层仅做粗粒度上限，细粒度限流在 LLM 客户端层。
 */
@Slf4j
public class WorkflowScheduler {

    private final AgentNodeRegistry registry;
    private final AgentLoopExecutor executor;
    private final Container container;
    private final Class<? extends AgentContext> contextType;
    private final ResourceOwnershipRegistry resourceOwnership;

    /** 全局最大并发工作流数 */
    private final Semaphore globalPermits;
    /** 工作流实例表：workflowId → instance */
    private final ConcurrentMap<String, WorkflowInstance> activeWorkflows = new ConcurrentHashMap<>();
    /** 每个 START 节点的状态：startNodeName → per-start state */
    private final ConcurrentMap<String, PerStartState> perStartStates = new ConcurrentHashMap<>();
    /** 已注册的 START 节点名 */
    private final Set<String> registeredStarts = ConcurrentHashMap.newKeySet();

    public WorkflowScheduler(AgentNodeRegistry registry,
                              AgentLoopExecutor executor,
                              Container container,
                              Class<? extends AgentContext> contextType,
                              ResourceOwnershipRegistry resourceOwnership,
                              int maxConcurrentWorkflows) {
        this.registry = registry;
        this.executor = executor;
        this.container = container;
        this.contextType = contextType;
        this.resourceOwnership = resourceOwnership;
        this.globalPermits = new Semaphore(maxConcurrentWorkflows, true);
        warnIfContextIsNotPrototype(contextType, maxConcurrentWorkflows);
    }

    /**
     * 上下文作用域检查：本调度器用 {@code container.getBean(contextType)} 取得上下文，
     * 若该 Bean 是<b>单例</b>，则所有并发工作流共用同一个实例 —— 一条改写状态，
     * 其余全部可见，互相污染。<br>
     * <br>
     * 只警告不阻断：单工作流场景下单例并不会出问题，强行报错会误伤既有应用。
     * 但默认并发上限是 16，踩中的概率很高，故在启动期明确提示修复方式。<br>
     * <br>
     * 读 {@code @Scope} 注解而非 {@code BeanDefinition}：二者一致（{@code BeanDefinitionFactory}
     * 即以该注解为 lifecycle 来源），而读注解不必给 {@link Container} 增加查询方法。
     */
    private static void warnIfContextIsNotPrototype(Class<? extends AgentContext> contextType,
                                                    int maxConcurrentWorkflows) {
        if (maxConcurrentWorkflows <= 1) {
            return;   // 不会并发，单例无害
        }
        Scope scope = contextType.getAnnotation(Scope.class);
        boolean prototype = scope != null && scope.value() == Lifecycle.PROTOTYPE;
        if (prototype) {
            return;
        }
        log.warn("上下文 [{}] 未声明 @Scope(Lifecycle.PROTOTYPE)，而当前并发上限为 {} —— "
                        + "所有工作流将共用同一个上下文实例，状态会互相污染。"
                        + "请给该上下文类加上 @Scope(Lifecycle.PROTOTYPE)。",
                contextType.getName(), maxConcurrentWorkflows);
    }

    /**
     * 注册 START 节点（框架启动时由 AgentRuntime 调用）。
     */
    public void registerStartNode(String startNodeName) {
        registeredStarts.add(startNodeName);
        perStartStates.computeIfAbsent(startNodeName, k -> new PerStartState());
    }

    /**
     * 启动一条工作流。<br>
     * 由 {@link StartTriggerSource} 在条件满足时调用，或由用户代码主动调用。<br>
     * 按该 START 的 {@link TriggerPolicy} 决定实际是否启动。
     *
     * @param startNodeName START 节点名
     * @return 工作流 ID（若被策略拒绝则返回 null）
     */
    public String startWorkflow(String startNodeName) {
        if (!registeredStarts.contains(startNodeName)) {
            throw new IllegalStateException("未注册的 START 节点: " + startNodeName);
        }
        PerStartState state = perStartStates.computeIfAbsent(startNodeName, k -> new PerStartState());
        TriggerPolicyType policy = state.triggerPolicyType;

        // 按策略决策
        switch (policy) {
            case REJECT -> {
                if (state.activeCount.get() > 0) {
                    log.debug("START [{}] REJECT 策略：已有运行中工作流，拒绝新触发", startNodeName);
                    return null;
                }
            }
            case PARALLEL -> {
                // 受 globalPermits 限流，不在此处额外限制
            }
            case QUEUE -> {
                // 排队：入队等待，异步执行
                state.queueLock.lock();
                try {
                    if (state.activeCount.get() > 0) {
                        state.queue.add(startNodeName);
                        log.debug("START [{}] QUEUE 策略：入队等待", startNodeName);
                        return null;  // 返回 null 但已排队，队列消费时会启动
                    }
                } finally {
                    state.queueLock.unlock();
                }
            }
            case ABORT_AND_RESTART -> {
                // 中断原工作流：找到该 START 的运行中工作流，设 cancelFlag
                abortRunningWorkflows(startNodeName);
            }
            case CUSTOM -> {
                if (state.customStrategy != null) {
                    TriggerContext ctx = new TriggerContextImpl(startNodeName,
                            state.activeCount.get(), activeWorkflows.size(), 1);
                    if (!state.customStrategy.shouldStart(ctx)) {
                        return null;
                    }
                }
            }
        }

        return doStart(startNodeName);
    }

    /**
     * 并发名额租约的实现：把 {@code globalPermits} 的"占用"与"线程"解耦。<br>
     * <br>
     * 工作流阻塞在 ENTRANCE（STDIN / 定时 / 监听）时 {@code park()} 归还名额，
     * 被唤醒要跑之前 {@code unpark()} 重新取得。{@code close()} 在流程结束时兜底回收，
     * 保证"还回去的名额"不会漏（漏了表现为并发上限越来越小，直到完全跑不动）。
     */
    private final class SemaphoreLease implements ConcurrencyLease {

        /** 当前是否持有名额 */
        private final java.util.concurrent.atomic.AtomicBoolean held =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        /** 流程是否已结束（结束后不再参与名额账务） */
        private final java.util.concurrent.atomic.AtomicBoolean closed =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        /** 调度器 acquire 成功后调用：把名额登记到租约名下 */
        void markHeld() {
            held.set(true);
        }

        @Override
        public void park() {
            if (closed.get()) {
                return;
            }
            if (held.compareAndSet(true, false)) {
                globalPermits.release();
            }
        }

        @Override
        public void unpark() {
            if (closed.get()) {
                return;
            }
            // 只有"已 park 出去"才需要取回；重复调用（未 park 就 unpark）是空操作
            if (held.compareAndSet(false, true)) {
                try {
                    globalPermits.acquire();
                } catch (InterruptedException e) {
                    // 中断不抛给入口循环：恢复标记即可，循环自己的取消标志会处理退出
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public void close() {
            closed.set(true);
            if (held.compareAndSet(true, false)) {
                globalPermits.release();
            }
        }
    }

    /**
     * 实际启动工作流：分配 ID、创建 context、spawn 虚拟线程执行。
     */
    private String doStart(String startNodeName) {
        String workflowId = IdGenerator.generate(Namespace.AGENT, ResourceType.AGENT_INSTANCE, 16);
        WorkflowInstance instance = new WorkflowInstance(workflowId, startNodeName);
        activeWorkflows.put(workflowId, instance);

        PerStartState state = perStartStates.get(startNodeName);
        state.activeCount.incrementAndGet();

        VirtualThreads.start(() -> {
            SemaphoreLease lease = new SemaphoreLease();
            try {
                globalPermits.acquire();
                lease.markHeld();
                try {
                    AgentContext context = container.getBean(contextType);
                    context.bindWorkflow(workflowId);
                    instance.bindContext(context);
                    executor.execute(startNodeName, context, lease);
                    instance.markCompleted();
                    log.info("工作流 [{}] 完成 (start={})", workflowId, startNodeName);
                } finally {
                    lease.close();
                }
            } catch (WorkflowCancelledException e) {
                instance.markCancelled();
                log.info("工作流 [{}] 已取消", workflowId);
            } catch (Throwable t) {
                instance.markFailed(t);
                log.error("工作流 [{}] 异常终止 (start={})", workflowId, startNodeName, t);
            } finally {
                activeWorkflows.remove(workflowId);
                state.activeCount.decrementAndGet();
                resourceOwnership.releaseAll(workflowId);
                // 若有排队的工作流，取出执行
                drainQueue(startNodeName, state);
            }
        });

        return workflowId;
    }

    /**
     * 恢复一条已存在的工作流（检查点 P2：跨重启 / 同进程崩溃后）。<br>
     * <br>
     * 与 {@link #startWorkflow} 的区别：不分配新的 workflowId，不新建上下文，
     * 而是用恢复点里还原出的状态与节点继续执行。<b>并发账务与清理逻辑与正常启动完全一致</b>
     * （占用 globalPermits、计入该 START 的 activeCount、结束时 drainQueue），
     * 否则恢复出来的工作流会绕过限流，成为统计之外的黑户。
     *
     * @param startNodeName 该工作流归属的 START 节点名（用于并发账务与触发策略归属）
     * @param point         恢复点
     * @return 是否成功受理（workflowId 已在运行中时返回 false）
     */
    public boolean resume(String startNodeName, ResumePoint point) {
        if (!registeredStarts.contains(startNodeName)) {
            throw new IllegalStateException("未注册的 START 节点: " + startNodeName);
        }
        if (activeWorkflows.containsKey(point.workflowId())) {
            log.warn("工作流 [{}] 已在运行中，拒绝重复恢复", point.workflowId());
            return false;
        }

        PerStartState state = perStartStates.computeIfAbsent(startNodeName, k -> new PerStartState());
        WorkflowInstance instance = new WorkflowInstance(point.workflowId(), startNodeName);
        activeWorkflows.put(point.workflowId(), instance);
        state.activeCount.incrementAndGet();

        VirtualThreads.start(() -> {
            SemaphoreLease lease = new SemaphoreLease();
            try {
                globalPermits.acquire();
                lease.markHeld();
                try {
                    AgentContext context = point.state();
                    context.bindWorkflow(point.workflowId());
                    instance.bindContext(context);
                    executor.resume(point, lease);
                    instance.markCompleted();
                    log.info("工作流 [{}] 恢复执行完成 (start={})", point.workflowId(), startNodeName);
                } finally {
                    lease.close();
                }
            } catch (WorkflowCancelledException e) {
                instance.markCancelled();
                log.info("工作流 [{}] 恢复后被取消", point.workflowId());
            } catch (Throwable t) {
                instance.markFailed(t);
                log.error("工作流 [{}] 恢复执行异常 (start={})", point.workflowId(), startNodeName, t);
            } finally {
                activeWorkflows.remove(point.workflowId());
                state.activeCount.decrementAndGet();
                resourceOwnership.releaseAll(point.workflowId());
                drainQueue(startNodeName, state);
            }
        });
        return true;
    }

    /**
     * 排出该 START 的等待队列。
     */
    private void drainQueue(String startNodeName, PerStartState state) {
        state.queueLock.lock();
        try {
            String next = state.queue.poll();
            if (next != null && state.activeCount.get() == 0) {
                doStart(startNodeName);
            }
        } finally {
            state.queueLock.unlock();
        }
    }

    /**
     * 中断指定 START 的所有运行中工作流。
     */
    private void abortRunningWorkflows(String startNodeName) {
        activeWorkflows.values().stream()
                .filter(w -> w.startNodeName().equals(startNodeName) && w.isRunning())
                .forEach(w -> {
                    AgentContext ctx = w.context();
                    if (ctx != null) {
                        ctx.cancel();  // 协作式中断：下一节点边界退出
                    }
                });
    }

    /**
     * 查询所有活跃工作流。
     */
    public List<WorkflowInstance> listActiveWorkflows() {
        return List.copyOf(activeWorkflows.values());
    }

    /**
     * 查询指定 START 节点当前是否应跳过轮询检查。<br>
     * 供 {@link StartTriggerSource} 轮询前预检：仅当策略为 REJECT 且已有
     * 运行中工作流时返回 true —— 此时触发必然被拒，跳过 condition() 求值，
     * 避免"条件满足 → REJECT 拒绝"每轮重复的无效轮询循环。<br>
     * PARALLEL（允许并发）、QUEUE（应入队）、ABORT_AND_RESTART（应中断重启）
     * 等策略不跳过，保持原有触发语义。
     */
    public boolean shouldSkipPolling(String startNodeName) {
        PerStartState state = perStartStates.get(startNodeName);
        if (state == null || state.triggerPolicyType != TriggerPolicyType.REJECT) {
            return false;
        }
        return state.activeCount.get() > 0;
    }

    /**
     * 取消指定工作流（协作式中断）。
     */
    public boolean kill(String workflowId) {
        WorkflowInstance w = activeWorkflows.get(workflowId);
        if (w == null || !w.isRunning()) return false;
        AgentContext ctx = w.context();
        if (ctx != null) {
            ctx.cancel();
            log.info("工作流 [{}] 取消请求已发送", workflowId);
            return true;
        }
        return false;
    }

    /** 优雅关闭第一阶段：给运行中工作流自然结束的时间窗 */
    private static final long SHUTDOWN_GRACE_MILLIS = 1000L;
    /** 第二阶段：发出协作式取消后，再给节点边界生效的时间窗 */
    private static final long SHUTDOWN_CANCEL_GRACE_MILLIS = 2000L;

    /**
     * 优雅关闭：两段式——先等自然结束，超时则协作式取消，再等一段后报告残留。<br>
     * <br>
     * 为什么不"只等"：之前是无脑等 30s，而阻塞在 STDIN 等输入的工作流永远不会自己结束，
     * 于是每次关机都白等满 30 秒。现在改为"等一小会儿 → 取消 → 再等一小会儿 → 汇报"，
     * 最坏 ~3s 完成，且结果（自然结束 / 被取消 / 未退出）在日志里明确区分。
     */
    public void shutdown() {
        int active = activeWorkflows.size();
        if (active == 0) {
            log.info("WorkflowScheduler 正常关闭：无运行中工作流");
            return;
        }
        log.info("WorkflowScheduler 正常关闭：{} 个工作流运行中，先等待 {} ms",
                active, SHUTDOWN_GRACE_MILLIS);
        if (awaitDrain(SHUTDOWN_GRACE_MILLIS)) {
            log.info("WorkflowScheduler 关闭完成：工作流已自然结束");
            return;
        }

        log.info("仍有 {} 个工作流未结束，发送取消请求", activeWorkflows.size());
        for (WorkflowInstance w : activeWorkflows.values()) {
            AgentContext ctx = w.context();
            if (ctx != null) {
                ctx.cancel();   // 协作式中断：下一节点边界退出（STDIN 会话最迟 poll 超时后退出）
            }
        }
        // 兜底：STDIN 会话卡在等待输入上，取消标记要等 poll 超时才会被看到；
        // 直接投递 EOF 哨兵让它立刻醒来（不依赖 close(System.in) 这种全局副作用）
        executor.wakeupStdin();

        if (awaitDrain(SHUTDOWN_CANCEL_GRACE_MILLIS)) {
            log.info("WorkflowScheduler 关闭完成：工作流已被取消退出");
            return;
        }

        log.warn("仍有 {} 个工作流未退出，随 JVM 退出被丢弃: {}",
                activeWorkflows.size(), activeWorkflows.keySet());
    }

    /**
     * 等待活跃工作流排空。
     *
     * @return true 表示已全部结束
     */
    private boolean awaitDrain(long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (!activeWorkflows.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return activeWorkflows.isEmpty();
            }
        }
        return activeWorkflows.isEmpty();
    }

    /**
     * 强制关闭：中断所有工作流。
     */
    public void shutdownNow() {
        log.info("WorkflowScheduler 强制关闭：中断 {} 个工作流", activeWorkflows.size());
        for (WorkflowInstance w : activeWorkflows.values()) {
            AgentContext ctx = w.context();
            if (ctx != null) ctx.cancel();
            w.markCancelled();
        }
        executor.wakeupStdin();
    }

    /**
     * 每个 START 节点的独立状态。
     */
    static class PerStartState {
        final java.util.concurrent.atomic.AtomicInteger activeCount =
                new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.ConcurrentLinkedQueue<String> queue =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        final ReentrantLock queueLock = new ReentrantLock();
        TriggerPolicyType triggerPolicyType = TriggerPolicyType.REJECT;
        TriggerStrategy customStrategy = null;
    }

    /**
     * 设置 START 节点的触发策略（由 AgentRuntime 在启动时根据注解装配）。
     */
    public void setTriggerPolicy(String startNodeName, TriggerPolicyType type, TriggerStrategy strategy) {
        PerStartState state = perStartStates.computeIfAbsent(startNodeName, k -> new PerStartState());
        state.triggerPolicyType = type;
        state.customStrategy = strategy;
    }

    /** TriggerContext 实现 */
    private record TriggerContextImpl(
            String startNodeName,
            int activeCount,
            int globalActiveCount,
            int maxConcurrentPerStart
    ) implements TriggerContext {}
}
