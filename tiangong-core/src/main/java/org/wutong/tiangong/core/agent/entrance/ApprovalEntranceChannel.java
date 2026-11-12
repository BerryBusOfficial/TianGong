package org.wutong.tiangong.core.agent.entrance;

import lombok.extern.slf4j.Slf4j;
import org.wutong.tiangong.common.agent.context.AgentContext;
import org.wutong.tiangong.common.util.JsonUtil;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 审批入口通道：阻塞等待人工审批决定。<br>
 * <br>
 * <h2>工作模型</h2>
 * <ul>
 *     <li>内部持 {@link BlockingQueue}{@code <ApprovalDecision>}，上层应用（如 GUI
 *         / 邮件回调 / IM 机器人）调 {@link #submit(ApprovalDecision)} 投递决定</li>
 *     <li>{@link #await(String, AgentContext)} 阻塞取一次决定（200ms 轮询保证
 *         取消/关闭信号能打进来），返回 {@code null} 表示通道已结束</li>
 *     <li>使用模式 {@link EntranceMode#WAIT_ONCE}：执行器取一次后继续主流程，
 *         不会再次回到本节点</li>
 * </ul>
 *
 * <h2>替换实现</h2>
 * 本通道是最小骨架实现。用户可实现自己的 {@link EntranceChannel}{@code <ApprovalDecision>}
 * （比如订阅企业 IM 消息、监听邮件回调），通过通道注册名
 *（{@link org.wutong.tiangong.annotations.annotation.framework.agent.loop.entrance.ApproveNode#channel()}）
 * 替换默认通道。
 *
 * <h2>不写检查点</h2>
 * 审批的等待记录（A8）由 {@code AgentLoopExecutor} 在调 {@code await()} 前后
 * 统一处理 —— 本通道只负责"取一次决定"。
 */
@Slf4j
public class ApprovalEntranceChannel implements EntranceChannel<ApprovalDecision> {

    /** await 轮询粒度：决定取消/关闭信号的最迟响应延迟 */
    static final long POLL_MILLIS = 200L;

    private final BlockingQueue<ApprovalDecision> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean closing = new AtomicBoolean(false);

    @Override
    public String name() {
        return "approval";
    }

    @Override
    public void start() {
    }

    @Override
    public void close() {
        closing.set(true);
        log.info("审批通道已关闭：等待中的 await 将在 ≤ 200ms 内退出");
    }

    /**
     * 阻塞取得一次审批决定。<br>
     * <br>
     * 返回 {@code null} 的三种情形：
     * <ul>
     *     <li>{@link AgentContext#isCancelled()} 变为 {@code true}</li>
     *     <li>通道已被 {@link #close()}</li>
     *     <li>等待被 {@link InterruptedException} 中断</li>
     * </ul>
     */
    @Override
    public ApprovalDecision await(String workflowId, AgentContext context) throws InterruptedException {
        while (!closing.get() && !context.isCancelled()) {
            ApprovalDecision decision = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
            if (decision != null) {
                return decision;
            }
            // 超时：回到循环顶部重新检查关闭/取消
        }
        return null;
    }

    @Override
    public String toBody(ApprovalDecision input) {
        // 序列化为 JSON，由执行器按入口节点参数类型反序列化
        try {
            return JsonUtil.toJson(input);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // 序列化失败应被当作"通道转换异常"冒泡 —— 框架会按工作流执行异常处理
            throw new RuntimeException("审批决定序列化失败", e);
        }
    }

    // isJsonBody 用接口默认 true —— ApprovalDecision 转 JSON 后按节点参数类型解析

    /**
     * 提交一次审批决定（上层应用调用）。<br>
     * <br>
     * 通道关闭后再次提交会被忽略。框架不主动调用此方法 —— 它是上层应用
     *（如审批 UI 提交回调、企业 IM 机器人收到"通过"消息）到通道的注入点。
     */
    public void submit(ApprovalDecision decision) {
        if (closing.get()) {
            log.warn("审批通道已关闭，忽略审批决定: approver={}", decision.getApprover());
            return;
        }
        queue.offer(decision);
    }

    /** 当前待处理的审批决定数（用于监控/调试） */
    public int pendingCount() {
        return queue.size();
    }
}