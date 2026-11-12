package org.wutong.tiangong.core.container.context;

import lombok.Getter;
import lombok.Setter;
import org.wutong.tiangong.core.container.context.framework.ApplicationFrameworkContext;
import org.wutong.tiangong.core.container.context.framework.ThreadContext;

public class ContextHolder {

    // 线程级
    private static final ThreadLocal<ThreadContext> thread = ThreadLocal.withInitial(ThreadContext::new);
    // 应用级
    @Setter
    @Getter
    private static ApplicationFrameworkContext application;

    public static ThreadContext getThread() {
        return thread.get();
    }

    /**
     * TaskExecutor 包装任务时挂载上下文
     */
    public static void setThreadContext(ThreadContext ctx) {
        thread.set(ctx);
    }

    public static void clearThread() {
        thread.remove();
    }

}
