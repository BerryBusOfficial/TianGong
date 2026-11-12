package com.taixu.tiangong.llm.event.result;

import com.taixu.tiangong.llm.api.response.chat.OllamaChatResponse;

import java.util.function.Consumer;

/**
 * Ollama 同步对话结果。<br>
 * 请求失败时构造 error 版本：{@link #onError} 回调错误（含错误码异常），{@link #join} 抛出。
 */
public class OllamaChatResult implements ChatResult<OllamaChatResponse> {

    private final OllamaChatResponse response;
    private final Throwable throwable;

    public OllamaChatResult(OllamaChatResponse response) {
        this(response, null);
    }

    public OllamaChatResult(OllamaChatResponse response, Throwable throwable) {
        this.response = response;
        this.throwable = throwable;
    }

    @Override
    public OllamaChatResult onData(Consumer<? super OllamaChatResponse> consumer) {
        if (throwable == null && response != null) {
            consumer.accept(response);
        }
        return this;
    }

    @Override
    public OllamaChatResult onComplete(Runnable consumer) {
        if (throwable == null) {
            consumer.run();
        }
        return this;
    }

    @Override
    public OllamaChatResult onError(Consumer<? super Throwable> consumer) {
        if (throwable != null) {
            consumer.accept(throwable);
        }
        return this;
    }

    @Override
    public OllamaChatResponse join() {
        if (throwable != null) {
            if (throwable instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(throwable);
        }
        return response;
    }

    @Override
    public void cancel() {
    }
}
