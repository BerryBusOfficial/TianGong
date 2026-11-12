package com.taixu.tiangong.annotations.annotation.framework.agent.interfaces;

/**
 * 事件驱动型启动条件：外部事件即时触发工作流启动，不参与轮询。<br>
 * <br>
 * 框架启动时调用 {@link #onTrigger(Runnable)} 注册回调，
 * 实现方在合适的外部事件（stdin 输入、API 请求、MQ 消息等）到来时调用 {@code callback.run()}。<br>
 * <br>
 * 回调由框架调度到独立虚拟线程执行，实现方无需关心线程管理。<br>
 * <br>
 * 示例（stdin 输入触发）：
 * <pre>{@code
 * @Component
 * public class OnStdinInput implements EventDrivenStartCondition {
 *     @Override
 *     public void onTrigger(Runnable callback) {
 *         new Thread(() -> {
 *             while (true) { System.in.read(); callback.run(); }
 *         }).start();
 *     }
 * }
 * }</pre>
 */
public interface EventDrivenStartCondition extends StartCondition {

    /**
     * 注册触发回调。框架在 {@code AgentRuntime} 启动时调用此方法。<br>
     * 实现方应保存 {@code callback}，在外部事件发生时调用 {@code callback.run()}。<br>
     * <br>
     * 注意：此方法应非阻塞，长时间监听应放到独立线程或虚拟线程中。
     *
     * @param callback 框架提供的回调，调用即触发对应工作流启动
     */
    void onTrigger(Runnable callback);

    /**
     * 事件驱动条件不走轮询，此方法返回值被框架忽略。
     */
    @Override
    default boolean condition() {
        return false;
    }
}
