package com.taixu.tiangong.core.container.context.framework;

/**
 * 启动策略：宿主对"框架自举行为"的控制面。<br>
 * <br>
 * <b>为什么需要它</b>：{@link ApplicationFrameworkContext#create} 默认按"单机自运行"
 * 假定工作 —— 起 API 服务器、阻塞 main 线程跑 AgentRuntime、注册 JVM 关机钩子。
 * 嵌入到别的宿主（如 Spring Boot 集成，由宿主触发框架自举并接管生命周期）时，
 * 这三件事的语义完全不同：API 层要被禁用（宿主提供 HTTP）、Runtime 要后台跑
 * （不能阻塞宿主启动）、关机钩子交给宿主（避免重复注册与双重收尾）。<br>
 * <br>
 * <b>默认值保证单机行为完全不变</b>：{@link #standalone()} 即历史行为。
 *
 * @param api           API 层策略：ALLOW = 允许 {@code @EnableApi} 起 HTTP 服务器；
 *                      FORBID = 禁用 API（含 {@code @EnableApi} 则启动期报错，
 *                      {@code @ApiEntranceNode}/{@code @ApiExportNode} 按
 *                      {@code tiangong.entrance.api.error.enable} 报错或退化为服务入口）
 * @param runtime       AgentRuntime 启动方式：BLOCKING = 阻塞调用线程（单机语义）；
 *                      ASYNC = 后台虚拟线程启动（宿主就绪后立即返回）；NONE = 不启动
 * @param jvmShutdownHooks 是否注册 JVM 关机钩子：单机 true；宿主接管生命周期时 false
 */
public record BootPolicy(
        ApiMode api,
        RuntimeStart runtime,
        boolean jvmShutdownHooks
) {

    /** 单机自运行（历史默认行为） */
    public static BootPolicy standalone() {
        return new BootPolicy(ApiMode.ALLOW, RuntimeStart.BLOCKING, true);
    }

    public enum ApiMode {
        ALLOW,
        FORBID
    }

    public enum RuntimeStart {
        BLOCKING,
        ASYNC,
        NONE
    }
}
