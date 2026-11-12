package com.taixu.tiangong.core.agent.runtime;

import lombok.extern.slf4j.Slf4j;
import com.taixu.tiangong.annotations.annotation.framework.agent.interfaces.EventDrivenStartCondition;
import com.taixu.tiangong.annotations.annotation.framework.agent.interfaces.StartCondition;
import com.taixu.tiangong.common.thread.VirtualThreads;
import com.taixu.tiangong.core.agent.AgentNodeDefinition;
import com.taixu.tiangong.core.agent.AgentNodeRegistry;
import com.taixu.tiangong.core.container.Container;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 启动触发源（方案 C 混合模式）：<br>
 * <br>
 * 两种触发通道：
 * <ul>
 *     <li>轮询通道：普通 {@link StartCondition}，按各自 {@code pollInterval()} 定时检查 {@code condition()}</li>
 *     <li>事件通道：{@link EventDrivenStartCondition}，框架注册回调，外部事件即时通知</li>
 * </ul>
 * <br>
 * 触发后通知 {@link WorkflowScheduler#startWorkflow} 启动新工作流。
 */
@Slf4j
public class StartTriggerSource {

    private final AgentNodeRegistry registry;
    private final Container container;
    private final WorkflowScheduler scheduler;
    private final ScheduledExecutorService pollScheduler;
    private final ConcurrentMap<String, ScheduledFuture<?>> pollingTasks = new ConcurrentHashMap<>();

    public StartTriggerSource(AgentNodeRegistry registry,
                              Container container,
                              WorkflowScheduler scheduler) {
        this.registry = registry;
        this.container = container;
        this.scheduler = scheduler;
        this.pollScheduler = Executors.newScheduledThreadPool(1, VirtualThreads.factory());
    }

    /**
     * 注册所有 START 节点的触发条件。<br>
     * 框架启动时由 {@link AgentRuntime} 调用。
     */
    public void registerAll() {
        for (AgentNodeDefinition node : registry.startNodes()) {
            register(node);
        }
        log.info("StartTriggerSource 注册完成：{} 个 START 节点", registry.startNodes().size());
    }

    /**
     * 注册单个 START 节点的触发条件。
     */
    public void register(AgentNodeDefinition startNode) {
        StartCondition condition = resolveCondition(startNode);

        if (condition instanceof EventDrivenStartCondition ed) {
            // 事件驱动：注册回调
            registerEventDriven(startNode.name(), ed);
        } else {
            // 普通轮询
            registerPolling(startNode.name(), condition);
        }
    }

    /**
     * 注册轮询条件：按 pollInterval 定时检查。<br>
     * REJECT 策略下已有活跃工作流时跳过 condition() 检查，避免无效轮询循环。
     */
    private void registerPolling(String nodeName, StartCondition condition) {
        Duration interval = condition.pollInterval();
        long delayMillis = interval.toMillis();

        ScheduledFuture<?> future = pollScheduler.scheduleWithFixedDelay(() -> {
            try {
                // REJECT 策略下已有活跃工作流时跳过（触发必然被拒，避免无效轮询循环）
                if (scheduler.shouldSkipPolling(nodeName)) {
                    return;
                }
                if (condition.condition()) {
                    log.debug("START [{}] 轮询条件满足，触发工作流", nodeName);
                    String wfId = scheduler.startWorkflow(nodeName);
                    if (wfId == null) {
                        log.debug("START [{}] 触发被策略拒绝", nodeName);
                    }
                }
            } catch (Throwable t) {
                log.error("START [{}] 轮询条件检查异常", nodeName, t);
            }
        }, delayMillis, delayMillis, TimeUnit.MILLISECONDS);

        pollingTasks.put(nodeName, future);
        log.info("START [{}] 注册轮询触发，间隔 {} ms", nodeName, delayMillis);
    }

    /**
     * 注册事件驱动条件：调用 onTrigger 注册回调。
     */
    private void registerEventDriven(String nodeName, EventDrivenStartCondition ed) {
        // 回调由 scheduler 在独立虚拟线程中执行（解耦）
        Runnable callback = () -> {
            log.debug("START [{}] 事件触发，启动工作流", nodeName);
            String wfId = scheduler.startWorkflow(nodeName);
            if (wfId == null) {
                log.debug("START [{}] 事件触发被策略拒绝", nodeName);
            }
        };
        try {
            ed.onTrigger(callback);
            log.info("START [{}] 注册事件驱动触发", nodeName);
        } catch (Throwable t) {
            log.error("START [{}] 事件驱动条件注册失败", nodeName, t);
        }
    }

    /**
     * 解析 START 节点的条件实例。<br>
     * StartCondition 实现类现在是 Bean，从容器获取；未指定用 DEFAULT。
     */
    @SuppressWarnings("unchecked")
    private StartCondition resolveCondition(AgentNodeDefinition startNode) {
        Class<? extends StartCondition> condClass = startNode.startConditionClass();
        if (condClass == null || condClass == StartCondition.class) {
            return StartCondition.DEFAULT;
        }
        // 从容器获取 Bean
        StartCondition bean = container.getBean(condClass);
        if (bean != null) {
            return bean;
        }
        // 兜底：反射实例化（非 Bean 的 StartCondition）
        try {
            return condClass.getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "无法获取 StartCondition 实例 [" + condClass.getName()
                            + "]，需注册为 Bean 或提供无参构造", e);
        }
    }

    /**
     * 关闭所有轮询任务。
     */
    public void shutdown() {
        for (Map.Entry<String, ScheduledFuture<?>> entry : pollingTasks.entrySet()) {
            entry.getValue().cancel(false);
        }
        pollScheduler.shutdown();
        log.info("StartTriggerSource 已关闭");
    }
}
