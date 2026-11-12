package com.taixu.tiangong.llm.event.result;

import com.taixu.tiangong.llm.api.response.chat.DeepseekChatResponse;

import java.util.function.Consumer;

/**
 * Deepseek 同步对话结果。<br>
 * 请求失败时构造 error 版本：{@link #onError} 回调错误（含错误码异常），{@link #join} 抛出。
 */
public class DeepseekChatResult implements ChatResult<DeepseekChatResponse> {

    private final DeepseekChatResponse response;
    private final Throwable throwable;

    public DeepseekChatResult(DeepseekChatResponse response) {
        this(response, null);
    }

    public DeepseekChatResult(DeepseekChatResponse response, Throwable throwable) {
        this.response = response;
        this.throwable = throwable;
    }

    @Override
    public DeepseekChatResult onData(Consumer<? super DeepseekChatResponse> consumer) {
        if (throwable == null && response != null) {
            consumer.accept(response);
        }
        return this;
    }

    @Override
    public DeepseekChatResult onComplete(Runnable consumer) {
        if (throwable == null) {
            consumer.run();
        }
        return this;
    }

    @Override
    public DeepseekChatResult onError(Consumer<? super Throwable> consumer) {
        if (throwable != null) {
            consumer.accept(throwable);
        }
        return this;
    }

    @Override
    public DeepseekChatResponse join() {
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
