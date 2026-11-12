package com.taixu.tiangong.retrieval;

/**
 * 检索/向量库适配失败。用运行时异常，避免每个 retriever 在接口上重复声明 throws。
 */
public class RetrievalException extends RuntimeException {

    public RetrievalException(String message) {
        super(message);
    }

    public RetrievalException(String message, Throwable cause) {
        super(message, cause);
    }
}
