package com.taixu.tiangong.trace;

/**
 * 回放比对失败：golden trace 与本次执行的 prompt hash/结果/工具链不一致。
 */
public class ReplayMismatchException extends RuntimeException {

    public ReplayMismatchException(String message) {
        super(message);
    }
}
