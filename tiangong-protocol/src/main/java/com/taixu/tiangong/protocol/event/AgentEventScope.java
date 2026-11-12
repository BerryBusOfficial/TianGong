package com.taixu.tiangong.protocol.event;

import java.util.function.Supplier;

/**
 * 事件作用域：把「当前这次运行的事件出口」挂在当前线程上。<br>
 * <br>
 * <h2>为什么必须能 capture</h2>
 * 流式读取运行在框架自己的虚拟线程里（{@code ChatStream.start()}），ThreadLocal
 * <b>不会</b>被自动继承。因此上游在创建流时先 {@link #capture()}，流线程启动后再
 * {@link #runWith} 绑定，事件才不会丢到另一个线程的空作用域里。<br>
 * <br>
 * 作用域只做「事件从哪来、属于哪次运行」，不负责分配 seq；分配由
 * {@link AgentEventJournal} 负责。
 */
public final class AgentEventScope {

    /**
     * 一次运行的绑定快照。
     *
     * @param sink  事件出口
     * @param runId 运行 id
     * @param turnId 本轮 id
     */
    public record Binding(AgentEventSink sink, String runId, String turnId) {
        public Binding {
            if (sink == null) {
                throw new IllegalArgumentException("sink 不能为空");
            }
        }
    }

    private static final ThreadLocal<Binding> CURRENT = new ThreadLocal<>();

    private AgentEventScope() {
    }

    /** 当前线程的绑定；没有则返回 null（纯 llm/单测环境允许不装事件协议） */
    public static Binding capture() {
        return CURRENT.get();
    }

    /** 绑定到当前线程，返回恢复句柄；关闭后恢复原绑定 */
    public static AutoCloseable open(Binding binding) {
        Binding previous = CURRENT.get();
        if (binding == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(binding);
        }
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    /** 发事件到当前线程的绑定；没有绑定则静默丢弃（事件是可选观测能力，不应阻断主链路） */
    public static void emit(AgentEvent event) {
        emit(CURRENT.get(), event);
    }

    /** 直接向指定绑定发事件，供跨线程回调使用 */
    public static void emit(Binding binding, AgentEvent event) {
        if (binding == null || event == null) {
            return;
        }
        binding.sink().emit(event);
    }

    /** 在绑定内执行；binding 为 null 时直接执行 */
    public static void runWith(Binding binding, Runnable action) {
        if (binding == null) {
            action.run();
            return;
        }
        try (AutoCloseable ignored = open(binding)) {
            action.run();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("恢复事件作用域失败", e);
        }
    }

    /** 在绑定内取返回值；binding 为 null 时直接执行 */
    public static <T> T callWith(Binding binding, Supplier<T> action) {
        if (binding == null) {
            return action.get();
        }
        try (AutoCloseable ignored = open(binding)) {
            return action.get();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("恢复事件作用域失败", e);
        }
    }
}
