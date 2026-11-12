package org.wutong.tiangong.annotations.annotation.framework.exception;

import lombok.extern.slf4j.Slf4j;
import org.wutong.tiangong.common.exception.SystemRuntimeException;

/**
 * 默认异常 fallback：{@code @ListenException} 未指定 fallback 时使用。<br>
 * 行为：log.error 记录异常 + 抛出 {@link SystemRuntimeException}（包装原异常）。
 */
@Slf4j
public class DefaultExceptionFallback implements ExceptionFallback {

    public static final DefaultExceptionFallback INSTANCE = new DefaultExceptionFallback();

    @Override
    public void onException(Throwable error) {
        log.error("异常监听捕获到未处理异常: {}", error.getMessage(), error);
        throw new SystemRuntimeException(error);
    }
}
