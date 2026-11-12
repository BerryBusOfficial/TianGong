package com.taixu.tiangong.llm.event;

import java.util.function.Consumer;

public interface Event {

    void cancel();

    Event onError(Consumer<? super Throwable> consumer);

}
