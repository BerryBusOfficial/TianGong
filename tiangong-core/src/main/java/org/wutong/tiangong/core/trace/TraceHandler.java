package org.wutong.tiangong.core.trace;

/**
 * 跟踪处理器：处理跟踪对象的具体细节。<br>
 * 用户实现关心的回调（全部 default 空实现），多个 handler 可并存（订阅者模式）。
 */
public interface TraceHandler {

    /**
     * 方法进入
     */
    default void onMethodEntry(TraceMethodEvent event) {
    }

    /**
     * 方法正常返回
     */
    default void onMethodExit(TraceMethodEvent event) {
    }

    /**
     * 方法抛异常
     */
    default void onMethodError(TraceMethodEvent event, Throwable error) {
    }

    /**
     * 字段读取（方法入口快照）
     */
    default void onFieldRead(TraceFieldEvent event) {
    }

    /**
     * 字段写入（方法出口对比发现变化）
     */
    default void onFieldWrite(TraceFieldEvent event) {
    }

    /**
     * 方法执行结束（成功或异常）时触发，携带计时信息。<br>
     * 与 onMethodExit/onMethodError 区别：本回调只关注耗时，不含 args/result/error。
     */
    default void onMethodTimed(TimerEvent event) {
    }

    /**
     * 方法执行超过 @Trace.timeout 阈值时触发（onMethodTimed 之后额外触发）。<br>
     * 仅当 @Trace(timeout > 0) 且 elapsed > threshold 时才会调用。
     */
    default void onMethodTimeout(TimerEvent event) {
    }
}
