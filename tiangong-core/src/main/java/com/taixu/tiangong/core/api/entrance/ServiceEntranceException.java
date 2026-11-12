package com.taixu.tiangong.core.api.entrance;

import com.taixu.tiangong.common.exception.SystemRuntimeException;

/**
 * 服务调用基础设施异常：地址未注册 / 地址歧义 / 入口已关闭 / 调用超时 / 等待被中断。<br>
 * <br>
 * <b>只用于桥自身故障</b>：业务异常（入口/链路节点抛出的）沿
 * {@link EntranceResponse#cause()} 原样透传，由调用方按真实类型 rethrow，不做包装。
 */
public class ServiceEntranceException extends SystemRuntimeException {

    public ServiceEntranceException(String message) {
        super(message);
    }

    public ServiceEntranceException(String message, Throwable cause) {
        super(message, cause);
    }
}
