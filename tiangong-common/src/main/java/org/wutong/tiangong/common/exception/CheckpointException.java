package org.wutong.tiangong.common.exception;

/**
 * 检查点异常：快照产出/还原失败、schema 版本不匹配、恢复点不可用时抛出。<br>
 * <br>
 * <b>契约要求（docs/checkpoint-contract.md A3/A4）</b>：检查点链路上的失败必须**响亮地失败**。
 * 禁止静默降级（返回原对象、返回 null、写入空快照）——那会让坏检查点在无人知晓的情况下落库，
 * 把"恢复出错"变成"恢复后状态莫名其妙"。
 */
public class CheckpointException extends SystemRuntimeException {

    public CheckpointException(String message) {
        super(message);
    }

    public CheckpointException(String message, Throwable cause) {
        super(message, cause);
    }
}
