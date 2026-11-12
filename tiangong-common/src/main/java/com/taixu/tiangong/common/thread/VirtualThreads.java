package com.taixu.tiangong.common.thread;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 虚拟线程统一入口：框架内所有虚拟线程都经此创建，命名统一为 {@link #PREFIX} + 自增序号。<br>
 * <br>
 * 为什么要收敛到一处：
 * <ul>
 *     <li>JDK 未提供修改虚拟线程默认名（{@code virtual-N}）的系统属性，默认名也看不出用途，
 *         日志里 {@code [virtual-150]} 无法定位来源；</li>
 *     <li>用自建 {@link ThreadFactory} 而非 {@code Thread.ofVirtual().name(prefix, start)}：
 *         后者的计数器是每个 Builder 实例私有的，多处分别创建会各自从 start 重新计数，
 *         产生重名线程；这里用全局 {@link AtomicLong} 保证序号在全进程内唯一。</li>
 * </ul>
 * <br>
 * 命名示例：{@code tiangong-virtual-12}。<br>
 * 注：虚拟线程恒为守护线程，随最后一个平台线程结束而终止，无需显式 setDaemon。
 */
public final class VirtualThreads {

    /** 虚拟线程统一命名前缀 */
    public static final String PREFIX = "tiangong-virtual-";

    /**
     * 全局序号：跨所有创建点共享，保证线程名唯一。
     */
    private static final AtomicLong SEQUENCE = new AtomicLong();

    /**
     * 全局共享工厂。无状态，可安全地被多个线程池/执行器复用。
     */
    private static final ThreadFactory FACTORY = task ->
            Thread.ofVirtual().name(nextName()).unstarted(task);

    private VirtualThreads() {
    }

    /**
     * 全局共享的虚拟线程工厂，适用于 {@code Executors.newThreadPerTaskExecutor}、
     * {@code newSingleThreadScheduledExecutor} 等任何需要 {@link ThreadFactory} 的场景。
     */
    public static ThreadFactory factory() {
        return FACTORY;
    }

    /**
     * 创建（未启动）一个虚拟线程，用于无法接入工厂的调用点。
     */
    public static Thread unstarted(Runnable task) {
        return Thread.ofVirtual().name(nextName()).unstarted(task);
    }

    /**
     * 创建并启动一个虚拟线程，替代 {@code Thread.startVirtualThread(task)}。
     *
     * @return 已启动的虚拟线程
     */
    public static Thread start(Runnable task) {
        Thread thread = unstarted(task);
        thread.start();
        return thread;
    }

    /**
     * 每次任务一个虚拟线程的执行器，语义等价于 {@code Executors.newVirtualThreadPerTaskExecutor()}。
     */
    public static ExecutorService newPerTaskExecutor() {
        return Executors.newThreadPerTaskExecutor(FACTORY);
    }

    /**
     * 取下一个线程名，供需要自行组装的场景使用。
     */
    public static String nextName() {
        return PREFIX + SEQUENCE.incrementAndGet();
    }
}
