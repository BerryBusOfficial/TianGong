package org.wutong.tiangong.core.agent.runtime;

import java.util.Optional;

/**
 * 框架全局变量存储：统一的键值存取，可在任何地方读写。<br>
 * <br>
 * 核心用途：
 * <ul>
 *     <li>信号总线语义：工作流 A 发信号 → 工作流 B 的 StartCondition 消费（跨工作流触发）</li>
 *     <li>框架级共享状态：全局计数器、状态标志等</li>
 * </ul>
 * <br>
 * 配置项 {@code agent.framework.global_store.mode}：
 * <ul>
 *     <li>{@code singleton}（默认）：进程内单例，基于 ConcurrentHashMap</li>
 *     <li>{@code distributed}：预留接口，本期不实现</li>
 * </ul>
 */
public interface GlobalVariableStore {

    /**
     * 存入值（覆盖式）。
     */
    <T> void put(String key, T value);

    /**
     * 读取值（非破坏性）。
     */
    <T> Optional<T> get(String key, Class<T> type);

    /**
     * 读取并删除（信号消费语义）：取出后该键被移除。
     */
    <T> Optional<T> consume(String key, Class<T> type);

    /**
     * 非破坏性查看最新值（与 {@link #get} 相同，语义别名）。
     */
    default <T> Optional<T> latest(String key, Class<T> type) {
        return get(key, type);
    }

    /**
     * 移除指定键。
     */
    void remove(String key);

    /**
     * 是否包含指定键。
     */
    boolean contains(String key);
}
