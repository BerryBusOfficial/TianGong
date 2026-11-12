package org.wutong.tiangong.core.agent.runtime;

/**
 * 工作流被取消异常：由 {@code AgentLoopExecutor} 在检测到
 * {@code AgentContext.isCancelled() == true} 时抛出，终止工作流执行。<br>
 * <br>
 * 这是协作式中断的信号异常，不应被 {@code @AgentNodeExceptionAdvice} 或
 * {@code @ListenException} 捕获——工作流已在节点边界退出。
 */
public class WorkflowCancelledException extends RuntimeException {

    public WorkflowCancelledException(String workflowId) {
        super("工作流 [" + workflowId + "] 已被取消（协作式中断）");
    }
}
