package com.taixu.tiangong.core.agent.runtime;

import com.taixu.tiangong.common.agent.context.AgentContext;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 工作流实例：记录一条工作流的运行时元数据。<br>
 * <br>
 * 生命周期：START 触发 → RUNNING → COMPLETED / FAILED / CANCELLED。
 */
public class WorkflowInstance {

    public enum Status { RUNNING, COMPLETED, FAILED, CANCELLED }

    private final String workflowId;
    private final String startNodeName;
    private final Instant startedAt;
    private final AtomicReference<Status> status;
    private volatile Instant finishedAt;
    private volatile Throwable error;
    /** 持有 context 引用，用于协作式中断（cancel()） */
    private volatile AgentContext context;

    public WorkflowInstance(String workflowId, String startNodeName) {
        this.workflowId = workflowId;
        this.startNodeName = startNodeName;
        this.startedAt = Instant.now();
        this.status = new AtomicReference<>(Status.RUNNING);
    }

    public String workflowId() {
        return workflowId;
    }

    public String startNodeName() {
        return startNodeName;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Status status() {
        return status.get();
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    public Throwable error() {
        return error;
    }

    public AgentContext context() {
        return context;
    }

    void bindContext(AgentContext context) {
        this.context = context;
    }

    void markCompleted() {
        finishedAt = Instant.now();
        status.set(Status.COMPLETED);
    }

    void markFailed(Throwable t) {
        finishedAt = Instant.now();
        this.error = t;
        status.set(Status.FAILED);
    }

    void markCancelled() {
        finishedAt = Instant.now();
        status.set(Status.CANCELLED);
    }

    boolean isRunning() {
        return status.get() == Status.RUNNING;
    }

    @Override
    public String toString() {
        return "Workflow[" + workflowId + "] start=" + startNodeName
                + " status=" + status.get()
                + " started=" + startedAt;
    }
}
