package org.wutong.tiangong.llm.event.result;

import org.wutong.tiangong.llm.api.response.chat.OpenaiChatResponse;

import java.util.function.Consumer;

/**
 * OpenAI 同步对话结果。<br>
 * 请求失败时构造 error 版本：{@link #onError} 回调错误（含错误码异常），{@link #join} 抛出。
 */
public class OpenaiChatResult implements ChatResult<OpenaiChatResponse> {

    private final OpenaiChatResponse response;
    private final Throwable throwable;

    public OpenaiChatResult(OpenaiChatResponse response) {
        this(response, null);
    }

    public OpenaiChatResult(OpenaiChatResponse response, Throwable throwable) {
        this.response = response;
        this.throwable = throwable;
    }

    @Override
    public OpenaiChatResult onData(Consumer<? super OpenaiChatResponse> consumer) {
        if (throwable == null && response != null) {
            consumer.accept(response);
        }
        return this;
    }

    @Override
    public OpenaiChatResult onComplete(Runnable consumer) {
        if (throwable == null) {
            consumer.run();
        }
        return this;
    }

    @Override
    public OpenaiChatResult onError(Consumer<? super Throwable> consumer) {
        if (throwable != null) {
            consumer.accept(throwable);
        }
        return this;
    }

    @Override
    public OpenaiChatResponse join() {
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
