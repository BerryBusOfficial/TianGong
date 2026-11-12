package org.wutong.tiangong.core.agent.entrance;

/**
 * 入口节点拿到输入后的执行模式 —— 决定"谁执行后续节点"。<br>
 * <br>
 * <b>为什么是契约（枚举）而非扩展点</b>：模式决定"主线程 / fork 副本 / 等待后继续"
 * 这些事情，牵着 A6（只有工作流主线程写检查点）、A8（等待记录的常驻与关闭）、
 * 并发账务（fork 副本的取消语义）。把模式开放给通道作者 = 把正确性外包。
 * 通道作者只需要实现 {@link EntranceChannel#await(String, org.wutong.tiangong.common.agent.context.AgentContext)},
 * 模式由框架固定为以下三种。
 */
public enum EntranceMode {

    /**
     * 串行会话循环：主执行线程持续阻塞在 {@code await()}，
     * 每轮（取输入 → 跑节点链到 END）执行完毕再次阻塞。<br>
     * 典型场景：STDIN CLI 会话。
     */
    SESSION_LOOP,

    /**
     * 每请求 fork：主线程在第一个 {@code await()} 处驻留（写一条等待记录），
     * 每来一次输入 fork 一份上下文副本到新线程跑后续节点链；主线程继续阻塞。
     * <b>fork 副本不写检查点</b>（A6：非主线程不产生记录）。<br>
     * 典型场景：HTTP 服务端入口。
     */
    FORK_PER_INPUT,

    /**
     * 一次性等待：主执行线程阻塞在 {@code await()}，取到一次输入后继续主流程，
     * 不会再次回到入口节点。<br>
     * 典型场景：人工审批节点。
     */
    WAIT_ONCE
}