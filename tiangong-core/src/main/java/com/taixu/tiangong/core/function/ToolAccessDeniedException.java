package com.taixu.tiangong.core.function;

/**
 * 工具调用被权限/审批拦截时抛出。<br>
 * 与「工具执行失败」区分开：调用方需要知道这是<b>策略拒绝</b>，不是业务异常，
 * 更不应该把拒绝原因当成普通的 error 再重试。
 */
public class ToolAccessDeniedException extends RuntimeException {

    public ToolAccessDeniedException(String message) {
        super(message);
    }
}
