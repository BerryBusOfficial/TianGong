package com.taixu.tiangong.llm.event.result;

import com.taixu.tiangong.llm.event.Event;

import java.util.function.Consumer;

public interface Result<T> extends Event {

    Result<T> onData(Consumer<? super T> consumer);

    Result<T> onComplete(Runnable consumer);

    @Override
    Result<T> onError(Consumer<? super Throwable> consumer);

    T join();

}
