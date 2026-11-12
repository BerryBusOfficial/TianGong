package org.wutong.tiangong.core.agent.entrance;

import lombok.extern.slf4j.Slf4j;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.entrance.ListenNodeHandler;
import org.wutong.tiangong.common.agent.context.AgentContext;
import org.wutong.tiangong.common.util.JsonUtil;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 外部事件监听通道：{@code @ListenNode} 背后的"取输入"实现。<br>
 * <br>
 * <h2>工作模型</h2>
 * <ul>
 *     <li>执行器进入 {@code @ListenNode} 时调 {@link #bind} —— 通道建好队列，
 *         调 {@code handler.listen(context, emitter)}（handler 只注册回调、立刻返回），
 *         之后事件到达时 handler 调 {@code emitter.accept(payload)} 投递</li>
 *     <li>{@link #await(String, AgentContext)} 阻塞取一次事件（200ms 轮询，
 *         保证取消/关闭信号能打进来）；<b>未绑定时返回 null</b>，执行器据此退出会话循环</li>
 *     <li>执行器退出本节点时调 {@link #unbind} —— 调 {@code handler.dispose()} 让它注销回调</li>
 * </ul>
 *
 * <h2>队列不设上限（刻意）</h2>
 * 事件是"外部世界已经发生的事实"，丢掉一个支付回调和一个不存在的回调，
 * 后果完全不同 —— 框架不替用户做背压决策。代价：handler 投得比工作流消费得快时队列会涨。
 * 真要限流请在 handler 侧做（这才是知道业务语义的那一层）。
 *
 * <h2>错误处理归执行器</h2>
 * {@code listen()} / {@code dispose()} 抛的异常<b>原样冒泡</b>给执行器，
 * 由工作流异常处理流程兜住 —— 通道不吞异常，否则"事件源根本没连上"会表现成"永远没事件"。
 */
@Slf4j
public class ListenEntranceChannel implements EntranceChannel<Object> {

    /** await 轮询粒度：决定取消/关闭信号的最迟响应延迟 */
    static final long POLL_MILLIS = 200L;

    /** workflowId → 绑定 */
    private final ConcurrentMap<String, Binding> bindings = new ConcurrentHashMap<>();

    private final AtomicBoolean closing = new AtomicBoolean(false);

    @Override
    public String name() {
        return "listen";
    }

    /**
     * 绑定事件源：建队列 → 调 {@code handler.listen(...)} → 返回。<br>
     * <br>
     * handler 抛异常时队列<b>不</b>注册（避免留下一个永远不会有人投喂的绑定），异常冒泡。
     *
     * @param workflowId 工作流实例 ID
     * @param handler    事件源（由执行器从容器取，不能反射 new）
     * @param context    工作流上下文
     */
    public void bind(String workflowId, ListenNodeHandler handler, AgentContext context) {
        if (workflowId == null || workflowId.isEmpty()) {
            throw new IllegalArgumentException("监听绑定需要 workflowId");
        }
        if (handler == null) {
            throw new IllegalArgumentException("监听绑定 [" + workflowId + "] 的 handler 为 null");
        }
        Binding binding = new Binding(workflowId, handler);
        // 先调 listen 再注册：listen 抛异常时不能留下一个"没人投喂"的空绑定，
        // 否则工作流会安静地等到天荒地老，看不出事件源根本没接上。
        handler.listen(context, binding.emitter);
        Binding prev = bindings.put(workflowId, binding);
        if (prev != null) {
            log.warn("工作流 [{}] 重复绑定监听，旧 handler 已 dispose", workflowId);
            disposeQuietly(prev);
        }
        log.debug("监听绑定已建立: workflow={} handler={}",
                workflowId, handler.getClass().getSimpleName());
    }

    /**
     * 解绑：调 {@code handler.dispose()} 并移除绑定。幂等 —— 未绑定时安全返回。
     */
    public void unbind(String workflowId) {
        Binding removed = bindings.remove(workflowId);
        if (removed != null) {
            disposeQuietly(removed);
            log.debug("监听绑定已解除: workflow={}", workflowId);
        }
    }

    /** 当前绑定数（监控/调试） */
    public int bindingCount() {
        return bindings.size();
    }

    /** 指定工作流当前积压的事件数 */
    public int pendingCount(String workflowId) {
        Binding binding = bindings.get(workflowId);
        return binding == null ? 0 : binding.queue.size();
    }

    @Override
    public void close() {
        closing.set(true);
        for (Binding binding : bindings.values()) {
            disposeQuietly(binding);
        }
        bindings.clear();
        log.info("监听通道已关闭：等待中的 await 将在 ≤ {}ms 内退出", POLL_MILLIS);
    }

    @Override
    public Object await(String workflowId, AgentContext context) throws InterruptedException {
        Binding binding = bindings.get(workflowId);
        // 未绑定 = 该工作流的通道已结束（解绑 / 通道关闭）→ 通知执行器退出会话循环
        if (binding == null) {
            return null;
        }
        while (!closing.get() && !context.isCancelled()) {
            Object payload = binding.queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
            if (payload != null) {
                return payload;
            }
            if (!bindings.containsKey(workflowId)) {
                return null;
            }
        }
        return null;
    }

    @Override
    public String toBody(Object input) {
        if (input == null) {
            return "";
        }
        try {
            return JsonUtil.toJson(input);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(
                    "监听事件序列化失败: " + input.getClass().getName(), e);
        }
    }

    /**
     * {@code dispose()} 的异常只记录不抛出：它发生在清理路径上，
     * 抛出去会盖掉工作流本身的退出原因。但必须打 WARN ——
     * 静默吞掉会让"监听器没关干净"变成最难查的那类泄漏。
     */
    private void disposeQuietly(Binding binding) {
        try {
            binding.handler.dispose();
        } catch (Throwable t) {
            log.warn("监听 handler dispose 失败: workflow={} handler={}",
                    binding.workflowId, binding.handler.getClass().getSimpleName(), t);
        }
    }

    /** 一条绑定：队列 + 投喂口 + handler。内部类 —— 投喂口要读通道的关闭标记 */
    private final class Binding {
        final String workflowId;
        final ListenNodeHandler handler;
        final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        final Consumer<Object> emitter = this::emit;

        Binding(String workflowId, ListenNodeHandler handler) {
            this.workflowId = workflowId;
            this.handler = handler;
        }

        /**
         * 事件投喂口：handler 持有的就是它。<br>
         * 通道已关闭时丢弃 —— 那时已没有工作流会来取，留着只会让队列无限涨。
         */
        void emit(Object payload) {
            if (closing.get()) {
                log.debug("监听通道已关闭，丢弃事件: workflow={}", workflowId);
                return;
            }
            queue.offer(payload);
        }
    }
}
