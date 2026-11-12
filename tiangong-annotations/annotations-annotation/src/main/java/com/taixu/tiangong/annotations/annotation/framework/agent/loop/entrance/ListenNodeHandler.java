package com.taixu.tiangong.annotations.annotation.framework.agent.loop.entrance;

import com.taixu.tiangong.common.agent.context.AgentContext;

import java.util.function.Consumer;

/**
 * {@code @ListenNode} 的事件源：用户实现"怎么接到外部事件"，框架负责"接到之后怎么跑工作流"。<br>
 * <br>
 * <h2>契约：注册回调后立刻返回</h2>
 * {@link #listen} <b>不是</b>"阻塞等事件"的方法 —— 它只负责把 emitter 挂到事件源上
 * （订阅 MQ、注册文件监听、起一个 HTTP 回调……）然后<b>立刻返回</b>。
 * 事件真正到达时调 {@code emitter.accept(payload)} 即可，一行代码唤醒工作流。<br>
 * <br>
 * 为什么不是"返回即唤醒"：那种写法要求 handler 自己阻塞，于是
 * ① 阻塞期间拿不到工作流取消信号 ② 一个 handler 只能服务一条工作流
 * ③ 框架无法在退出时干净地回收资源。注册-回调把这三点都交还给框架。
 *
 * <h2>幂等与多实例</h2>
 * 同一个 handler 类被多个 {@code @ListenNode} 引用、或同一工作流多次进入本节点时，
 * {@link #listen} 会被调用多次，每次的
 * {@code context} 与 {@code emitter} 都不同 —— 实现类要用
 * {@code context.workflowId()}（或自己持有的 emitter 引用）区分，不要假设"全局只有一个"。
 *
 * <h2>错误处理归执行器</h2>
 * {@code listen()} 抛异常时由执行器按工作流异常处理流程兜住（不会静默吞掉）；
 * 事件回调里抛的异常同理。节点只管自己 —— 重试、退避、告警都是执行器/应用的事。
 */
public interface ListenNodeHandler {

    /**
     * 注册事件回调。<b>必须立刻返回</b>，不得在此阻塞等待事件。<br>
     * <br>
     * 框架在「工作流第一次到达该 {@code @ListenNode}」时调用一次；
     * 配对 {@link #dispose()} 在工作流退出该节点时调用一次。
     *
     * @param context 当前工作流上下文（可取其 {@code workflowId()} 区分多实例）
     * @param emitter 事件投喂口：事件到达时调 {@code emitter.accept(payload)}，
     *                payload 会被序列化后交给入口节点方法的第二个参数
     */
    void listen(AgentContext context, Consumer<Object> emitter);

    /**
     * 注销回调、释放资源。工作流退出 {@code @ListenNode} 时由框架调用一次
     * （正常退出 / 取消 / 关闭 / 异常都覆盖）。<br>
     * 默认空操作；实现类有外部连接（MQ consumer、文件 watcher、socket）时必须覆写。
     */
    default void dispose() {
    }
}
