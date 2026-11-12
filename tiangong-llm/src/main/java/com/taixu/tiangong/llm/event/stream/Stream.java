package com.taixu.tiangong.llm.event.stream;

import com.taixu.tiangong.llm.event.Event;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

public interface Stream<T> extends Event {

    Stream<T> onData(Consumer<? super T> consumer);

    Stream<T> onComplete(Runnable consumer);

    Stream<T> start();

    Stream<T> onError(Consumer<? super Throwable> consumer);

    /**
     * 等流结束，返回所有 chunk
     */
    CompletableFuture<List<T>> collect();

    /**
     * 取流的结束信号。<br>
     * <b>不阻塞</b>——拿到 future 立刻返回，流的读取在另一个虚拟线程里跑；
     * 想等流结束请用 {@link #await()}，或自行 {@code future().join()}。
     */
    CompletableFuture<Void> future();

    /**
     * 阻塞等流结束，流内异常以运行时异常抛出（取消视为正常结束）。<br>
     * 等价于 {@code future().join()}，但把 {@code CompletionException} 拆开，直接抛原始异常。
     */
    default void await() {
        try {
            future().join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        }
    }

}
