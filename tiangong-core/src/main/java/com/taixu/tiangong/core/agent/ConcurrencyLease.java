package com.taixu.tiangong.core.agent;

/**
 * 并发名额租约：把「占一个并发槽」与「占一个线程」解耦。<br>
 * <br>
 * <h2>为什么需要</h2>
 * 工作流阻塞在 ENTRANCE（STDIN 等输入 / 定时等下一拍 / 监听等外部事件）时，
 * 它<b>占着一个线程但什么也不做</b>。虚拟线程 parked 很便宜，真正贵的是并发名额 ——
 * 名额被占满后新的工作流根本起不来。一条"每 5 分钟跑一次"的定时工作流如果一直占着名额，
 * 16 个这样的定时任务就能把整个应用堵死。<br>
 * <br>
 * 于是：进入阻塞前 {@link #park()} 把名额还回去，拿到输入要跑之前 {@link #unpark()}
 * 重新取回。槽与线程从此不再绑死。
 *
 * <h2>契约</h2>
 * <ul>
 *     <li>{@code park()} / {@code unpark()} 必须<b>成对</b>出现，且都发生在同一条
 *         工作流线程上（名额没有跨线程转移的语义）</li>
 *     <li>{@link #unpark()} 可能阻塞（名额被别的工作流占着）—— 这正是限流的意义，
 *         不是死锁：parked 的工作流都已经把名额还出来了</li>
 *     <li>{@link #close()} 由调度器在流程结束时调用一次，保证"还回去的名额"最终被回收</li>
 * </ul>
 */
public interface ConcurrencyLease {

    /** 空实现：没有并发上限时使用（测试、直接调用执行器的场景） */
    ConcurrencyLease NOOP = new ConcurrencyLease() {
        @Override
        public void park() {
        }

        @Override
        public void unpark() {
        }

        @Override
        public void close() {
        }
    };

    /**
     * 进入阻塞：把并发名额还回去。重复调用只有第一次生效。
     */
    void park();

    /**
     * 离开阻塞：重新取得并发名额。<b>可能阻塞</b>直到有名额可用。<br>
     * 等待被中断时恢复中断标记并立即返回（不抛受检异常 ——
     * 调用方是入口循环，中断由它自己的取消标志处理）。
     */
    void unpark();

    /**
     * 流程结束：若仍持有名额则释放。必须调用一次，否则名额泄漏
     * （表现为并发上限越来越小，直到应用完全跑不动）。
     */
    void close();
}
