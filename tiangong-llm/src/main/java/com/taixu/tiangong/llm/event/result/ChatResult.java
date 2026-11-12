package com.taixu.tiangong.llm.event.result;

import java.util.function.Consumer;

public interface ChatResult<T> extends Result<T> {

    @Override
    ChatResult<T> onData(Consumer<? super T> consumer);

    @Override
    ChatResult<T> onComplete(Runnable consumer);

    @Override
    ChatResult<T> onError(Consumer<? super Throwable> consumer);

}
