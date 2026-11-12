package org.wutong.tiangong.annotations.annotation.framework.agent.interfaces;

import java.time.Duration;

/**
 * 工作流启动条件：由 {@code StartTriggerSource} 定期检测，
 * {@code condition()} 返回 true 时触发对应 START 节点启动一条工作流。<br>
 * <br>
 * 实现类须注册为 Bean（可 {@code @Inject} 框架组件如 {@code GlobalVariableStore}）。<br>
 * <br>
 * 两种触发模式：
 * <ul>
 *     <li>普通条件：框架按 {@link #pollInterval()} 轮询调用 {@link #condition()}</li>
 *     <li>事件驱动条件：实现 {@link EventDrivenStartCondition}，外部事件即时触发</li>
 * </ul>
 */
public interface StartCondition {

    /** 默认条件：恒为 true（无条件 START 节点） */
    StartCondition DEFAULT = new StartCondition() {
        @Override public boolean condition() { return true; }
        @Override public Duration pollInterval() { return Duration.ofMillis(500); }
    };

    /**
     * 是否满足启动条件。
     *
     * @return true 则框架启动该 START 节点对应的工作流
     */
    boolean condition();

    /**
     * 轮询间隔（仅普通条件模式生效，事件驱动条件不轮询）。<br>
     * 默认 500ms，实现类可按需覆盖（如定时巡检类可设较长间隔）。
     */
    default Duration pollInterval() {
        return Duration.ofMillis(500);
    }
}
