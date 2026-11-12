package com.taixu.tiangong.spring;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import com.taixu.tiangong.common.exception.SystemException;
import com.taixu.tiangong.common.exception.SystemRuntimeException;
import com.taixu.tiangong.core.api.entrance.ServiceEntranceRegistry;
import com.taixu.tiangong.core.container.context.framework.ApplicationFrameworkContext;
import com.taixu.tiangong.core.container.context.framework.BootPolicy;
import com.taixu.tiangong.core.thread.TaskExecutor;
import com.taixu.tiangong.spring.bridge.SpringWriteProcessor;
import com.taixu.tiangong.spring.bridge.SharedInstanceSpace;
import com.taixu.tiangong.spring.config.SpringConfigBridge;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 天工运行时（Spring 侧的触发者与持有者）。<br>
 * <br>
 * <h2>触发，而不是托管</h2>
 * 天工按自己的流程自举：装载配置 → 扫描 → 建容器 → 后台启动 AgentRuntime。
 * Spring 只做两件事：在单例就绪后<b>触发</b>（{@link SmartInitializingSingleton}），
 * 在容器关闭时<b>通知收尾</b>（{@link DisposableBean}）。<br>
 * <br>
 * <h2>启动策略</h2>
 * {@code BootPolicy(FORBID, ASYNC, false)}：API 禁用（宿主提供 HTTP）、
 * AgentRuntime 后台启动（不阻塞 Spring 启动）、不注册 JVM 关机钩子（由 Spring 生命周期驱动）。<br>
 * <br>
 * <h2>时序</h2>
 * <pre>
 * Spring 单例就绪（@ServiceAgent 代理已注入）
 *   → 本类自举：配置桥 → 天工容器（refresh）→ AgentRuntime 后台起
 *   → 工作流跑到 @ServiceEntranceNode，注册服务地址
 * Spring ready
 * 关闭：DisposableBean → AgentRuntime.shutdown → 框架执行器收尾
 * </pre>
 */
@Slf4j
public class TianGongRuntime implements SmartInitializingSingleton, DisposableBean {

    private final Environment environment;
    private final TianGongProperties properties;
    private final ApplicationContext applicationContext;
    private final SharedInstanceSpace space;
    private final Class<?> appClass;
    private final List<String> scanPackages;

    private volatile ApplicationFrameworkContext context;

    public TianGongRuntime(Environment environment, TianGongProperties properties,
                           ApplicationContext applicationContext, SharedInstanceSpace space) {
        this.environment = environment;
        this.properties = properties;
        this.applicationContext = applicationContext;
        this.space = space;

        Class<?> app = TianGongAppInfo.appClass();
        this.appClass = app != null ? app : TianGongRuntime.class;

        Set<String> packages = new LinkedHashSet<>();
        packages.add(this.appClass.getPackageName());
        packages.addAll(TianGongAppInfo.packages());
        packages.addAll(properties.getPackages());
        this.scanPackages = List.copyOf(packages);
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!properties.isEnabled()) {
            log.info("tiangong.enabled=false，跳过 TianGong 自举");
            return;
        }
        log.info("TianGong 自举开始：扫描包 {}（API 禁用模式；AgentRuntime 后台启动）", scanPackages);
        try {
            SpringConfigBridge.apply(appClass, environment, properties);
        } catch (SystemException e) {
            throw new SystemRuntimeException("TianGong 配置装载失败: " + e.getMessage(), e);
        }

        ApplicationFrameworkContext ctx = ApplicationFrameworkContext.create(
                scanner -> scanPackages.forEach(scanner::acceptPackages),
                container -> container.addAnnotationProcessor(new SpringWriteProcessor(applicationContext)),
                new BootPolicy(BootPolicy.ApiMode.FORBID, BootPolicy.RuntimeStart.ASYNC, false));
        this.context = ctx;
        // 实例桥收尾：归属登记 + @TianGongWrite 延迟注入点回填（必须在容器 refresh 之后）
        space.onTianGongReady(ctx);
        log.info("TianGong 自举完成（AgentRuntime 已在后台运行，服务入口随工作流启动注册）");
    }

    /**
     * 框架上下文；未完成自举时调用会抛出明确异常（而不是 NPE）。
     */
    public ApplicationFrameworkContext context() {
        ApplicationFrameworkContext ctx = context;
        if (ctx == null) {
            throw new SystemRuntimeException(
                    "TianGong 运行时尚未启动：@EnableTianGong 应用在自举完成前调用了天工能力");
        }
        return ctx;
    }

    /**
     * 框架上下文；未就绪时返回 null（供桥内部判断时序用，不抛异常）。
     */
    public ApplicationFrameworkContext contextOrNull() {
        return context;
    }

    /**
     * 是否已完成自举。
     */
    public boolean isReady() {
        return context != null;
    }

    /**
     * 共享实例空间：跨容器注入的中枢（归属登记 / 延迟回填 / 服务端点视图）。
     */
    public SharedInstanceSpace space() {
        return space;
    }

    /** 服务入口注册表（服务桥的寻址中枢）。 */
    public ServiceEntranceRegistry registry() {
        return context().getServiceEntranceRegistry();
    }

    /** 服务调用等待出口的超时（{@code tiangong.service.timeout-seconds}）。 */
    public long serviceTimeoutSeconds() {
        return properties.getServiceTimeoutSeconds();
    }

    /**
     * 关闭：先停 AgentRuntime（协作式取消工作流），再收尾框架执行器与沙箱。<br>
     * JVM 关机钩子在天工侧已关闭（BootPolicy），关闭只走这一条路径，不重复收尾。
     */
    @Override
    public void destroy() {
        ApplicationFrameworkContext ctx = context;
        if (ctx == null) {
            return;
        }
        log.info("TianGong 关闭：停止 AgentRuntime 与框架执行器");
        try {
            if (ctx.getAgentRuntime() != null) {
                ctx.getAgentRuntime().shutdown();
            }
            for (TaskExecutor executor : ctx.getTaskExecutorRegistry().values()) {
                executor.shutdown();
            }
            ctx.getSandboxRegistry().closeAll();
        } catch (Exception e) {
            log.warn("TianGong 关闭过程中出现异常（继续收尾）: {}", e.getMessage(), e);
        }
    }
}
