package org.wutong.tiangong.core.thread;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.wutong.tiangong.common.thread.VirtualThreads;
import org.wutong.tiangong.core.container.context.ContextHolder;
import org.wutong.tiangong.core.container.context.framework.ThreadContext;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 框架异步任务执行器。<br>
 * 默认虚拟线程；可注入自定义 ExecutorService（如固定线程池）由用户自行配置。<br>
 * 提交任务时自动创建/注册/挂载 ThreadContext，任务结束后自动清理，生命周期完整。<br>
 * 异常处理遵循 Spring Boot 行为：记录日志 + 传播到 Future（调用方 get() 可捕获）。
 */
@Slf4j
public class TaskExecutor {

    @Getter
    private final String name;
    private final ExecutorService executor;
    @Getter
    private final ThreadContextRegistry registry;
    private final AtomicLong completedCount = new AtomicLong();

    /**
     * 默认：虚拟线程池
     */
    public TaskExecutor() {
        this("default", VirtualThreads.newPerTaskExecutor(), new ThreadContextRegistry());
    }

    /**
     * 用户自定义线程池（如固定线程池）
     */
    public TaskExecutor(String name, ExecutorService executor) {
        this(name, executor, new ThreadContextRegistry());
    }

    public TaskExecutor(String name, ExecutorService executor, ThreadContextRegistry registry) {
        this.name = name;
        this.executor = executor;
        this.registry = registry;
    }

    public Future<?> submit(Runnable task) {
        return executor.submit(wrap(task));
    }

    public <T> Future<T> submit(Callable<T> task) {
        return executor.submit(wrap(task));
    }

    /**
     * 活跃任务数（固定线程池用 TPE 统计；虚拟线程回退到注册表大小）
     */
    public int getActiveCount() {
        if (executor instanceof ThreadPoolExecutor tpe) {
            return tpe.getActiveCount();
        }
        return registry.size();
    }

    public long getCompletedCount() {
        return completedCount.get();
    }

    // ========== 生命周期 ==========

    public void shutdown() {
        executor.shutdown();
    }

    public List<Runnable> shutdownNow() {
        return executor.shutdownNow();
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return executor.awaitTermination(timeout, unit);
    }

    private Runnable wrap(Runnable task) {
        return () -> {
            ThreadContext ctx = new ThreadContext();
            registry.register(ctx);
            ContextHolder.setThreadContext(ctx);
            try {
                task.run();
            } catch (Exception e) {
                log.error("后台任务执行异常 [{}]", ctx.getId(), e);
                throw e;                      // 传播到 Future，调用方 get() 可捕获
            } finally {
                ContextHolder.clearThread();
                registry.unregister(ctx.getId());
                completedCount.incrementAndGet();
            }
        };
    }

    private <T> Callable<T> wrap(Callable<T> task) {
        return () -> {
            ThreadContext ctx = new ThreadContext();
            registry.register(ctx);
            ContextHolder.setThreadContext(ctx);
            try {
                return task.call();
            } catch (Exception e) {
                log.error("后台任务执行异常 [{}]", ctx.getId(), e);
                throw e;
            } finally {
                ContextHolder.clearThread();
                registry.unregister(ctx.getId());
                completedCount.incrementAndGet();
            }
        };
    }
}
