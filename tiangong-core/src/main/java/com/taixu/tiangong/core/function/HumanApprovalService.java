package com.taixu.tiangong.core.function;

import com.taixu.tiangong.common.agent.call.AgentCallContext;
import com.taixu.tiangong.common.agent.tool.ToolApprovalDecision;
import com.taixu.tiangong.common.agent.tool.ToolApprovalHandler;
import com.taixu.tiangong.common.agent.tool.ToolApprovalRequest;
import com.taixu.tiangong.common.util.JsonUtil;
import com.taixu.tiangong.trace.TextRedactor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 人机协同审批服务：把同步的 {@link ToolApprovalHandler} 变成一个可查询、可由外部系统
 * 审批的待办队列。<br>
 * <br>
 * <h2>工作方式</h2>
 * 工具线程调用 {@link #decide} 时创建一个 pending ticket 并阻塞等待；审批 UI / IM 机器人 /
 * HTTP 端点拿到 {@link #pending()} 的 id 后调用 {@link #approve} 或 {@link #reject}。
 * 超时、线程中断、服务关闭都按<b>拒绝</b>收口 —— 审批链路故障不能变成提权通道。<br>
 * <br>
 * <b>边界</b>：pending 在内存里，进程重启即丢；跨实例审批需要把 ticket 落库并共享事件总线。
 * 本类不假装支持。
 */
public final class HumanApprovalService implements ToolApprovalHandler, AutoCloseable {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    private final Duration timeout;
    private final ConcurrentMap<String, Ticket> tickets = new ConcurrentHashMap<>();

    public HumanApprovalService() {
        this(DEFAULT_TIMEOUT);
    }

    public HumanApprovalService(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("审批超时时间必须为正数");
        }
        this.timeout = timeout;
    }

    @Override
    public ToolApprovalDecision decide(ToolApprovalRequest request) {
        if (request == null) {
            return ToolApprovalDecision.DENIED;
        }
        String id = "appr-" + UUID.randomUUID();
        CompletableFuture<ToolApprovalDecision> future = new CompletableFuture<>();
        Ticket ticket = new Ticket(id, request, Instant.now(), future);
        tickets.put(id, ticket);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.complete(ToolApprovalDecision.DENIED);
            return ToolApprovalDecision.DENIED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.complete(ToolApprovalDecision.DENIED);
            return ToolApprovalDecision.DENIED;
        } catch (ExecutionException e) {
            return ToolApprovalDecision.DENIED;
        } finally {
            tickets.remove(id);
        }
    }

    /** 通过审批；id 不存在/已决定时返回 false */
    public boolean approve(String id, String approver) {
        return complete(id, ToolApprovalDecision.APPROVED, approver);
    }

    /** 拒绝审批；id 不存在/已决定时返回 false */
    public boolean reject(String id, String approver) {
        return complete(id, ToolApprovalDecision.DENIED, approver);
    }

    private boolean complete(String id, ToolApprovalDecision decision, String approver) {
        if (id == null) {
            return false;
        }
        Ticket ticket = tickets.get(id);
        if (ticket == null) {
            return false;
        }
        boolean completed = ticket.future.complete(decision);
        if (completed) {
            ticket.decidedBy = approver;
            ticket.decidedAt = Instant.now();
        }
        return completed;
    }

    /** 当前待审批列表（脱敏参数摘要） */
    public List<PendingApproval> pending() {
        List<PendingApproval> list = new ArrayList<>();
        for (Ticket ticket : tickets.values()) {
            if (!ticket.future.isDone()) {
                list.add(ticket.view());
            }
        }
        list.sort(Comparator.comparing(PendingApproval::createdAt));
        return list;
    }

    public Optional<PendingApproval> find(String id) {
        Ticket ticket = id == null ? null : tickets.get(id);
        return ticket == null || ticket.future.isDone()
                ? Optional.empty() : Optional.of(ticket.view());
    }

    @Override
    public void close() {
        tickets.values().forEach(ticket -> ticket.future.complete(ToolApprovalDecision.DENIED));
        tickets.clear();
    }

    public Duration timeout() {
        return timeout;
    }

    private static final class Ticket {

        private final String id;
        private final ToolApprovalRequest request;
        private final Instant createdAt;
        private final CompletableFuture<ToolApprovalDecision> future;
        private volatile String decidedBy;
        private volatile Instant decidedAt;

        private Ticket(String id, ToolApprovalRequest request, Instant createdAt,
                       CompletableFuture<ToolApprovalDecision> future) {
            this.id = id;
            this.request = request;
            this.createdAt = createdAt;
            this.future = future;
        }

        private PendingApproval view() {
            AgentCallContext context = request.context();
            return new PendingApproval(
                    id,
                    request.toolName(),
                    context == null ? null : context.userId(),
                    context == null ? null : context.tenantId(),
                    context == null ? null : context.traceId(),
                    argumentsPreview(request.arguments()),
                    createdAt);
        }

        private static String argumentsPreview(Map<String, Object> arguments) {
            try {
                return TextRedactor.redact(JsonUtil.toJson(arguments), 500);
            } catch (Exception e) {
                return TextRedactor.redact(String.valueOf(arguments), 500);
            }
        }
    }

    /**
     * 审批待办视图（不含完整参数原文；只给脱敏摘要）。
     */
    public record PendingApproval(
            String id,
            String toolName,
            String userId,
            String tenantId,
            String traceId,
            String argumentsPreview,
            Instant createdAt
    ) {
    }
}
