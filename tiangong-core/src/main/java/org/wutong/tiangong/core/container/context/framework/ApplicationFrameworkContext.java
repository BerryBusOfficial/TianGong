package org.wutong.tiangong.core.container.context.framework;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.wutong.tiangong.annotations.annotation.definition.BeanDefinition;
import org.wutong.tiangong.annotations.annotation.definition.Lifecycle;
import org.wutong.tiangong.annotations.annotation.framework.ConditionalOnClass;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.entrance.ApiEntranceNode;
import org.wutong.tiangong.annotations.annotation.framework.agent.loop.entrance.ApiExportNode;
import org.wutong.tiangong.annotations.annotation.framework.enable.*;
import org.wutong.tiangong.annotations.processor.registry.BeanDefinitionRegistry;
import org.wutong.tiangong.annotations.scanner.EnableMetadata;
import org.wutong.tiangong.annotations.scanner.ImportMetadata;
import org.wutong.tiangong.annotations.scanner.TianGongScanner;
import org.wutong.tiangong.common.agent.checkpoint.ContextSnapshotter;
import org.wutong.tiangong.common.agent.checkpoint.JsonContextSnapshotter;
import org.wutong.tiangong.common.exception.SystemRuntimeException;
import org.wutong.tiangong.common.sandbox.Sandbox;
import org.wutong.tiangong.common.thread.VirtualThreads;
import org.wutong.tiangong.core.agent.AgentLoopExecutor;
import org.wutong.tiangong.core.agent.AgentNodeDefinition;
import org.wutong.tiangong.core.agent.processor.AgentNodeAnnotationProcessor;
import org.wutong.tiangong.core.agent.runtime.AgentRuntime;
import org.wutong.tiangong.core.agent.AgentNodeRegistry;
import org.wutong.tiangong.core.agent.NodeExceptionAdviceProcessor;
import org.wutong.tiangong.core.agent.NodeExceptionAdviceRegistry;
import org.wutong.tiangong.core.api.ApiHttpServer;
import org.wutong.tiangong.core.api.entrance.EntranceRouteRegistry;
import org.wutong.tiangong.core.api.entrance.ServiceEntranceRegistry;
import org.wutong.tiangong.core.config.ConfigHolder;
import org.wutong.tiangong.core.checkpoint.CheckpointRecord;
import org.wutong.tiangong.core.checkpoint.CheckpointService;
import org.wutong.tiangong.core.checkpoint.CheckpointStore;
import org.wutong.tiangong.core.checkpoint.SnapshotPolicy;
import org.wutong.tiangong.core.checkpoint.StorageCheckpointStore;
import org.wutong.tiangong.core.config.ConfigHolder;
import org.wutong.tiangong.core.container.Container;
import org.wutong.tiangong.core.container.FrameworkContainer;
import org.wutong.tiangong.core.container.context.ContextHolder;
import org.wutong.tiangong.core.container.process.impl.*;
import org.wutong.tiangong.core.function.FunctionRegistry;
import org.wutong.tiangong.core.llm.FunctionToolLoop;
import org.wutong.tiangong.core.llm.LlmRegistry;
import org.wutong.tiangong.core.script.ScriptRuntimeRegistry;
import org.wutong.tiangong.core.skill.SkillRegistry;
import org.wutong.tiangong.core.scheduled.ScheduledAnnotationProcessor;
import org.wutong.tiangong.core.scheduled.ScheduledTaskRegistry;
import org.wutong.tiangong.core.scheduled.ScheduledTaskScheduler;
import org.wutong.tiangong.core.sandbox.CommandLineResolver;
import org.wutong.tiangong.core.sandbox.SandboxRegistry;
import org.wutong.tiangong.core.storage.MapStorage;
import org.wutong.tiangong.core.storage.Storage;
import org.wutong.tiangong.core.storage.StorageRecord;
import org.wutong.tiangong.core.thread.TaskExecutor;
import org.wutong.tiangong.core.thread.TaskExecutorRegistry;
import org.wutong.tiangong.core.thread.ThreadContextRegistry;
import org.wutong.tiangong.core.trace.TraceHandlerRegistry;
import org.wutong.tiangong.llm.event.stream.ToolLoopSupport;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 框架应用程序级上下文，统一的上下文获取入口
 */
@Getter
@RequiredArgsConstructor
@Slf4j
public final class ApplicationFrameworkContext implements FrameworkContext {

    @Getter(AccessLevel.NONE)
    private final Storage<?, ?> storage;
    private final TianGongScanner scanner;
    private final Container container;
    private final LlmRegistry llmRegistry;
    private final TaskExecutor taskExecutor;
    private final TaskExecutorRegistry taskExecutorRegistry;
    private final ThreadContextRegistry threadContextRegistry;
    private final FunctionRegistry functionRegistry;
    private final EntranceRouteRegistry routeRegistry;
    /**
     * 服务入口注册表：进程内服务调用（{@code @ServiceEntranceNode} 与退化的
     * {@code @ApiEntranceNode}）的寻址中枢。<br>
     * 单机启动也会创建（空表无副作用）；宿主模式由桥接层经它提交服务调用。
     */
    private final ServiceEntranceRegistry serviceEntranceRegistry;
    private final AgentNodeRegistry agentNodeRegistry;
    private final NodeExceptionAdviceRegistry nodeExceptionAdviceRegistry;
    private final AgentLoopExecutor agentLoopExecutor;
    /**
     * AgentRuntime：{@code @EnableAgentLoop} 开启时非 null。<br>
     * 单机模式 BLOCKING 启动（本方法内阻塞）；宿主模式由宿主控制启停
     * （{@code BootPolicy.runtime}），因此需要暴露给宿主（Spring 关闭时调 shutdown）。
     */
    private final AgentRuntime agentRuntime;
    /**
     * 沙箱注册表。<br>
     * 由 {@code @RequiredArgsConstructor} 纳入构造参数，{@code @Getter} 生成
     * {@code getSandboxRegistry()}。声明在<b>最后</b>，保证它是构造函数的末位参数，
     * 不干扰既有调用点的位置。<br>
     * 关闭时机见 {@link #registerShutdownHooks} 中的 hook —— 沙箱可能持有容器、
     * 连接池等外部资源，须在 JVM 退出前释放。
     */
    private final SandboxRegistry sandboxRegistry;

    /**
     * &#064;EnableApi 开关：从扫描器元数据中检查，任一标注且 value=true 即开启 API 层。<br>
     * <br>
     * <b>必须走扫描器，不能另起一次 ClassGraph</b>：以前这里 private 地 new 一个 ClassGraph
     * 并硬编码 {@code acceptPackages("org.wutong.tiangong")} —— 而扫描器的包是可扩展的
     * （{@code run(mainClass)} 会追加启动类所在包）。于是启动类在业务包里时，
     * 节点、Bean 都正常注册了，唯独 API 层判定为「未开启」，HTTP server 静默不启动。
     * 三个 &#064;Enable*（Scheduling / Mcp / Api）里只有它走过这条错路。
     */
    private static boolean isApiEnabled(TianGongScanner scanner) {
        return scanner.getEnableMetadata().stream()
                .anyMatch(meta -> meta.enableAnnotation() == EnableApi.class && meta.enabled());
    }

    /**
     * @EnableScheduling 开关：从扫描器元数据中检查（@FrameworkEnable 纯标记，由 TianGongScanner 收集）。
     */
    private static boolean isSchedulingEnabled(TianGongScanner scanner) {
        return scanner.getEnableMetadata().stream()
                .anyMatch(meta -> meta.enableAnnotation() == EnableScheduling.class && meta.enabled());
    }

    public static ApplicationFrameworkContext create() {
        return create(s -> {
        }, c -> {
        });
    }

    public static ApplicationFrameworkContext create(
            Consumer<? super TianGongScanner> configurator,
            Consumer<? super FrameworkContainer> containerConfiger
    ) {
        return create(configurator, containerConfiger, BootPolicy.standalone());
    }

    /**
     * 带启动策略的创建入口（宿主集成用）。<br>
     * {@link BootPolicy} 控制 API 层 / AgentRuntime 启动方式 / JVM 关机钩子；
     * 两参重载 = {@link BootPolicy#standalone()}，单机行为完全不变。
     */
    public static ApplicationFrameworkContext create(
            Consumer<? super TianGongScanner> configurator,
            Consumer<? super FrameworkContainer> containerConfiger,
            BootPolicy bootPolicy
    ) {
        BeanDefinitionRegistry registry = new BeanDefinitionRegistry();
        ThreadContextRegistry threadContextRegistry = new ThreadContextRegistry();
        TaskExecutorRegistry executorRegistry = new TaskExecutorRegistry(threadContextRegistry);
        FunctionRegistry functionRegistry = new FunctionRegistry();
        TraceHandlerRegistry traceHandlerRegistry = new TraceHandlerRegistry();
        EntranceRouteRegistry routeRegistry = new EntranceRouteRegistry();
        ServiceEntranceRegistry serviceEntranceRegistry = new ServiceEntranceRegistry();
        AgentNodeRegistry agentNodeRegistry = new AgentNodeRegistry();
        NodeExceptionAdviceRegistry nodeExceptionAdviceRegistry = new NodeExceptionAdviceRegistry();
        SandboxRegistry sandboxRegistry = new SandboxRegistry();

        TianGongScanner scanner = TianGongScanner.load(
                registry, c -> {
                    c.acceptPackages("org.wutong.tiangong");
                    configurator.accept(c);
                }
        );

        LlmRegistry llmRegistry = new LlmRegistry();
        llmRegistry.load();   // 扫描注解 + 读配置 + 创建客户端

        TaskExecutor defaultExecutor = new TaskExecutor("default",
                VirtualThreads.newPerTaskExecutor(),
                threadContextRegistry);
        executorRegistry.register("default", defaultExecutor);

        boolean apiAnnotated = isApiEnabled(scanner);   // 复用扫描器结果，不另起一次扫描
        if (bootPolicy.api() == BootPolicy.ApiMode.FORBID && apiAnnotated) {
            throw new SystemRuntimeException(
                    "检测到 @EnableApi，但当前为 API 禁用模式（宿主提供 HTTP 能力，框架不再起 API 服务）："
                            + "请删除 @EnableApi；需要对外暴露能力请改用服务入口 @ServiceEntranceNode",
                    new IllegalStateException());
        }
        boolean apiEnabled = bootPolicy.api() == BootPolicy.ApiMode.ALLOW && apiAnnotated;
        boolean schedulingEnabled = isSchedulingEnabled(scanner);

        ScheduledTaskRegistry scheduledTaskRegistry = new ScheduledTaskRegistry();

        FrameworkContainer container = FrameworkContainer.create(
                registry, c -> {
                    c.addAnnotationProcessor(new InjectProcessor(llmRegistry, functionRegistry));
                    c.addAnnotationProcessor(new ValueProcessor());
                    c.addAnnotationProcessor(new ConfigurationBindProcessor());
                    c.addAnnotationProcessor(new AgentThreadPoolProcessor(executorRegistry));
                    c.addAnnotationProcessor(new AgentFunctionProcessor(functionRegistry));
                    c.addAnnotationProcessor(new TraceHandlerProcessor(traceHandlerRegistry));
                    c.addAnnotationProcessor(new AgentNodeAnnotationProcessor(agentNodeRegistry));
                    c.addAnnotationProcessor(new NodeExceptionAdviceProcessor(nodeExceptionAdviceRegistry));
                    if (schedulingEnabled) {
                        c.addAnnotationProcessor(new ScheduledAnnotationProcessor(scheduledTaskRegistry));
                    }
                    containerConfiger.accept(c);
                }
        );

        container.setTaskExecutorRegistry(executorRegistry);
        container.setTraceHandlerRegistry(traceHandlerRegistry);
        applyBuiltInEnables(scanner, container);

        // 技能注册表先于 refresh 创建并注册进容器。
        // 两个原因：① @AutoConfiguration 在 refresh 之前执行（enableConfigure），
        //    tiangong-skill 这类「目录式技能」模块必须能在那个时机拿到它；
        // ② Bean 形态的技能要等 refresh 之后才能收集（见下方 skillRegistry.loadBeans）。
        //
        // 两步缺一不可：Container.register 只写单例缓存，而 getBean(name) 会先查
        // BeanDefinition —— 只 register 不建定义，取出来是 null。补上 BeanDefinition 后，
        // refresh() 走到它时 singletonCache 已存在实例（computeIfAbsent 直接命中），
        // 不会另建一个把已装配的技能弄丢。
        SkillRegistry skillRegistry = new SkillRegistry();
        registry.register(BeanDefinition.builder()
                .beanName(SkillRegistry.BEAN_NAME)
                .beanClass(SkillRegistry.class)
                .lifecycle(Lifecycle.SINGLETON)
                .lazyInit(false)
                .build());
        container.register(SkillRegistry.BEAN_NAME, skillRegistry);

        // 脚本运行时注册表：同一个「先建后填」模式，同一个 BeanDefinition 陷阱
        ScriptRuntimeRegistry scriptRuntimeRegistry = new ScriptRuntimeRegistry();
        registry.register(BeanDefinition.builder()
                .beanName(ScriptRuntimeRegistry.BEAN_NAME)
                .beanClass(ScriptRuntimeRegistry.class)
                .lifecycle(Lifecycle.SINGLETON)
                .lazyInit(false)
                .build());
        container.register(ScriptRuntimeRegistry.BEAN_NAME, scriptRuntimeRegistry);

        // 沙箱注册表一并注册进容器：tiangong-skill 的脚本运行时要在沙箱里执行第三方脚本，
        // 需要能拿到它（沙箱实例必须走注册表，不能反射 new，否则 @Inject 字段为 null）。
        registry.register(BeanDefinition.builder()
                .beanName(SandboxRegistry.BEAN_NAME)
                .beanClass(SandboxRegistry.class)
                .lifecycle(Lifecycle.SINGLETON)
                .lazyInit(false)
                .build());
        container.register(SandboxRegistry.BEAN_NAME, sandboxRegistry);

        // 函数注册表注册进容器：工具映射这类功能要在运行期按名字查工具
        // （MCP 工具注册晚于 refresh，只能现查现用），组件拿不到注册表就只能层层传参。
        registry.register(BeanDefinition.builder()
                .beanName(FunctionRegistry.BEAN_NAME)
                .beanClass(FunctionRegistry.class)
                .lifecycle(Lifecycle.SINGLETON)
                .lazyInit(false)
                .build());
        container.register(FunctionRegistry.BEAN_NAME, functionRegistry);

        // 工具循环：带工具的流式请求 → 模型调工具 → 框架执行注册表里的函数 → 结果回放续轮，
        // 直到模型不再请求工具 —— 节点只写一次 stream()，不再需要 decide/call_tool 这类节点骨架。
        // 执行策略（FunctionRegistry + 反射调用 + 消息回放）在 core，流层只负责串轮次，
        // 所以通过 ToolLoopSupport 静态装入；agent.tools.auto-loop=false 可整体关闭。
        ToolLoopSupport.install((toolRequest, toolClient) ->
                isAutoToolLoopEnabled() ? new FunctionToolLoop(toolClient, toolRequest, functionRegistry) : null);

        // 节点链迭代上限：历史上这里写死 DEFAULT_MAX_ITERATIONS（100）且没有任何配置入口，
        // 「一个节点内部循环处理 N 个任务」的写法没问题，但真把循环铺在链路上的图会撞顶
        // 并抛 IllegalStateException。现在可用 agent.max-iterations 调整，不配则保持原默认值。
        int maxIterations = ConfigHolder.getValueOrDefault(
                "agent.max-iterations", Integer.class, AgentLoopExecutor.DEFAULT_MAX_ITERATIONS);
        if (maxIterations <= 0) {
            throw new IllegalStateException("agent.max-iterations 必须为正数: " + maxIterations);
        }
        AgentLoopExecutor agentLoopExecutor = new AgentLoopExecutor(
                agentNodeRegistry, nodeExceptionAdviceRegistry,
                maxIterations, routeRegistry);
        // 把容器交给执行器：@ListenNode(listenOn = Xxx.class) 要按类型取 handler Bean。
        // 只传引用不在此处 getBean —— 此刻还没 refresh，Bean 尚未就绪；
        // 真正取用发生在工作流跑到该节点时。
        agentLoopExecutor.setContainer(container);
        // 服务入口注册表交给执行器：@ServiceEntranceNode（含退化的 @ApiEntranceNode）经它寻址。
        agentLoopExecutor.setServiceEntranceRegistry(serviceEntranceRegistry);

        Storage<?, ?> storage = new MapStorage<>();
        FrameworkConfiguratorImpl frameworkConfigurator = enableConfigure(scanner, container, llmRegistry, functionRegistry,
                executorRegistry, threadContextRegistry, registry, storage, agentLoopExecutor);

        // AgentRuntime：@EnableAgentLoop 未开启时为 null；启动方式/关机钩子由 BootPolicy 决定
        AgentRuntime agentRuntime = frameworkConfigurator.getAgentRuntime();
        if (agentRuntime != null) {
            agentRuntime.setJvmShutdownHooks(bootPolicy.jvmShutdownHooks());
        }

        if (bootPolicy.jvmShutdownHooks()) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                for (TaskExecutor executor : executorRegistry.values()) {
                    executor.shutdown();
                    try {
                        executor.awaitTermination(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        executor.shutdownNow();
                    }
                }
                // 沙箱可能持有容器 / 连接池等外部资源，须在 JVM 退出前释放。
                // closeAll() 内部逐个容错，单个沙箱失败不影响其余。
                sandboxRegistry.closeAll();
            }, "tiangong-shutdown-hook"));
        }

        ApplicationFrameworkContext ctx = new ApplicationFrameworkContext(
                storage,
                scanner,
                container,
                llmRegistry,
                defaultExecutor,
                executorRegistry,
                threadContextRegistry,
                functionRegistry,
                routeRegistry,
                serviceEntranceRegistry,
                agentNodeRegistry,
                nodeExceptionAdviceRegistry,
                agentLoopExecutor,
                agentRuntime,
                sandboxRegistry
        );
        ContextHolder.setApplication(ctx);

        container.refresh();

        // 所有节点注册完成后，校验 @NodeNext 引用合法性（启动期，不被异常注解捕获）
        agentNodeRegistry.validateNextReferences();

        // API 禁用模式下的入口策略校验：@ApiEntranceNode/@ApiExportNode 报错或允许退化
        applyEntrancePolicy(bootPolicy, agentNodeRegistry, agentLoopExecutor);

        // 检查点在容器 refresh 之后装配：此时用户声明的 ContextSnapshotter / CheckpointStore
        // Bean 已可解析，框架据此决定用默认实现还是用户实现
        applyCheckpoint(scanner, container, agentLoopExecutor, storage);

        // 沙箱扩展点同样在 refresh 之后装配，理由同上
        applySandboxExtensions(container, sandboxRegistry);

        // 技能收集的第二批：Bean 形态的 Skill（refresh 后才可解析）
        skillRegistry.loadBeans(registry, container);

        // 脚本运行时的实现（shell / node …）同样在 refresh 之后收集
        scriptRuntimeRegistry.loadBeans(registry, container);

        if (apiEnabled) {
            ApiHttpServer apiServer = new ApiHttpServer(
                    routeRegistry, apiHost(), apiPort(), apiResponseTimeoutSeconds());
            apiServer.start();
            if (bootPolicy.jvmShutdownHooks()) {
                Runtime.getRuntime().addShutdownHook(new Thread(apiServer::close, "tiangong-api-shutdown"));
            }
        }

        if (schedulingEnabled) {
            ScheduledTaskScheduler taskScheduler = new ScheduledTaskScheduler();
            taskScheduler.start(scheduledTaskRegistry);
            if (bootPolicy.jvmShutdownHooks()) {
                Runtime.getRuntime().addShutdownHook(new Thread(taskScheduler::close, "tiangong-scheduling-shutdown"));
            }
        }

        // @EnableAgentLoop → 按 BootPolicy 启动 AgentRuntime：
        //   单机 BLOCKING（阻塞 main 线程，历史行为）；宿主 ASYNC（后台虚拟线程）；NONE 由宿主控制
        if (agentRuntime != null) {
            switch (bootPolicy.runtime()) {
                case BLOCKING -> agentRuntime.start();
                case ASYNC -> VirtualThreads.start(agentRuntime::start);
                case NONE -> log.info("BootPolicy.runtime=NONE：AgentRuntime 未自动启动（由宿主控制）");
            }
        }
        return ctx;
    }

    /**
     * agent.api.host，缺失/为 null/空时默认 127.0.0.1
     */
    private static String apiHost() {
        String host = ConfigHolder.getValueOrDefault("agent.api.host", String.class, null);
        return host == null || host.isBlank() ? "127.0.0.1" : host;
    }

    /**
     * agent.api.port，默认 2315（避开常见 Web 框架端口）
     */
    private static int apiPort() {
        return ConfigHolder.getValueOrDefault("agent.api.port", Integer.class, 2315);
    }

    /**
     * agent.tools.auto-loop：流式请求带工具时，框架是否自动执行模型请求的工具并续轮。<br>
     * <br>
     * 默认开启 —— 工具注册（@AgentFunction）本就是"这些函数可被模型调用"的声明，
     * 下发工具的请求自然期望模型拿到执行结果；关闭它退回"单轮流"：模型请求工具时
     * 只是把调用透给回调，执行由应用自己编排（旧的手动 ToolNode 链路）。
     */
    private static boolean isAutoToolLoopEnabled() {
        return ConfigHolder.getValueOrDefault("agent.tools.auto-loop", Boolean.class, true);
    }

    /**
     * agent.api.response-timeout-seconds：等 EXPORT 节点回传响应的超时（秒），默认 60。<br>
     * <br>
     * 必须可配：一轮 LLM（尤其带思考、带工具调用）动辄几十秒，写死 60 秒会稳定地把
     * 慢模型打成 504 —— 而工作流其实还在跑，只是回话晚了。超时只影响"何时放弃等待"，
     * 不影响工作流本身，因此放宽它是安全的。
     */
    private static long apiResponseTimeoutSeconds() {
        // 用 Integer 读：YAML 里的整数默认解析成 Integer，直接要 Long 会因类型不符拿到 null
        Integer seconds = ConfigHolder.getValueOrDefault(
                "agent.api.response-timeout-seconds", Integer.class,
                (int) ApiHttpServer.DEFAULT_RESPONSE_TIMEOUT_SECONDS);
        return seconds == null
                ? ApiHttpServer.DEFAULT_RESPONSE_TIMEOUT_SECONDS
                : seconds.longValue();
    }

    /**
     * API 禁用模式（宿主集成）下校验 API 入口节点：<br>
     * <ul>
     *     <li>含 {@code @ApiEntranceNode}/{@code @ApiExportNode} 且
     *         {@code tiangong.entrance.api.error.enable=true}（默认）→ 启动期直接报错；</li>
     *     <li>置 false → WARN 并允许退化：执行期按服务入口语义处理
     *         （url = agent:// + route，见 {@code AgentLoopExecutor.setApiForbidden}）。</li>
     * </ul>
     * 报错为默认：退化是兼容旧应用的逃生门，不是推荐路径 —— 默认放行会让
     * "我配的是 HTTP 入口"这种误解静默存活到运行期。
     */
    private static void applyEntrancePolicy(BootPolicy bootPolicy, AgentNodeRegistry nodeRegistry,
                                            AgentLoopExecutor executor) {
        if (bootPolicy.api() != BootPolicy.ApiMode.FORBID) {
            return;
        }
        List<String> apiNodes = nodeRegistry.values().stream()
                .filter(n -> n.method().getAnnotation(ApiEntranceNode.class) != null
                        || n.method().getAnnotation(ApiExportNode.class) != null)
                .map(AgentNodeDefinition::name)
                .toList();
        if (apiNodes.isEmpty()) {
            return;
        }
        boolean errorEnabled = ConfigHolder.getValueOrDefault(
                "tiangong.entrance.api.error.enable", Boolean.class, true);
        if (errorEnabled) {
            throw new SystemRuntimeException(
                    "检测到 API 入口节点 " + apiNodes + "，但当前为 API 禁用模式（宿主提供 HTTP 能力）；"
                            + "请改用 @ServiceEntranceNode/@ServiceExportNode，"
                            + "或设置 tiangong.entrance.api.error.enable=false 允许退化为服务入口",
                    new IllegalStateException());
        }
        executor.setApiForbidden(true);
        log.warn("API 禁用模式：{} 个 API 入口节点将退化为服务入口（url = agent:// + route）：{}",
                apiNodes.size(), apiNodes);
    }

    /**
     * 沙箱扩展点装配：命令解析器 + 沙箱实例的容器优先解析。<br>
     * <br>
     * 两项都遵循框架既有规则（与 {@code CheckpointStore} / {@code ContextSnapshotter} 一致）：
     * <b>优先取容器 Bean，取不到才回退默认行为</b>。
     *
     * <h3>命令解析器</h3>
     * 应用把 {@code CommandLineResolver} 实现标 {@code @Component} 即可，框架自动采用。
     * 未提供时<b>不在启动期报错</b>——多数应用根本没有 COMMAND 节点，强制要求会误伤；
     * 报错推迟到真正执行时，且信息里指明补救方式。
     *
     * <h3>沙箱实例</h3>
     * {@code @Sandbox(box = Xxx.class)} 解析时优先取容器 Bean，取不到才反射 new。
     * 这不是可选优化：沙箱实现里 {@code @Inject} 一个客户端或配置 Bean 是很自然的写法，
     * 若一律反射 new，这些字段会<b>静默为 null</b>，直到执行时才炸出 NPE。
     */
    private static void applySandboxExtensions(Container container, SandboxRegistry registry) {
        CommandLineResolver resolver = container.getBean(CommandLineResolver.class);
        if (resolver == null) {
            log.debug("未检测到 CommandLineResolver Bean；"
                    + "若工作流含 COMMAND 节点，执行时会报明确错误");
        } else {
            registry.setCommandLineResolver(resolver);
            log.info("命令解析器已装配: {}", resolver.getClass().getSimpleName());
        }

        registry.setInstanceSupplier(type -> {
            Sandbox bean = container.getBean(type);
            return bean != null ? bean : registry.instantiate(type);
        });
    }

    /**
     * 检查点装配（docs/checkpoint-contract.md）。<br>
     * <br>
     * 开启条件：扫描到 {@code @EnableCheckpoint}。关闭时不装配任何东西，
     * 执行器中的检查点调用全部退化为空操作，行为与引入检查点之前一致。<br>
     * <br>
     * 扩展点解析遵循框架既有规则（与 {@code StartCondition} 一致）：
     * <b>优先取容器 Bean，取不到才回退默认实现</b>。
     */
    private static void applyCheckpoint(TianGongScanner scanner, Container container,
                                        AgentLoopExecutor executor, Storage<?, ?> storage) {
        boolean enabled = scanner.getEnableMetadata().stream()
                .anyMatch(meta -> meta.enableAnnotation() == EnableCheckpoint.class && meta.enabled());
        if (!enabled) {
            log.debug("未检测到 @EnableCheckpoint，检查点功能关闭");
            return;
        }

        ContextSnapshotter snapshotter = container.getBean(ContextSnapshotter.class);
        if (snapshotter == null) {
            snapshotter = new JsonContextSnapshotter();
        }

        CheckpointStore store = container.getBean(CheckpointStore.class);
        if (store == null) {
            @SuppressWarnings("unchecked")
            Storage<CheckpointRecord, String> checkpointStorage =
                    (Storage<CheckpointRecord, String>) storage;
            store = new StorageCheckpointStore(checkpointStorage, false);
        }

        // 快照粒度策略同属扩展点：用户声明 @Component 实现即覆盖默认的 ALWAYS
        SnapshotPolicy policy = container.getBean(SnapshotPolicy.class);
        if (policy == null) {
            policy = SnapshotPolicy.ALWAYS;
        }

        CheckpointService service = new CheckpointService(store, snapshotter, policy);
        executor.setCheckpointService(service);
        log.info("检查点已启用：snapshotter={}（schema v{}），store={}，策略={}，跨进程存活={}，告警阈值={} 字符",
                snapshotter.getClass().getSimpleName(), snapshotter.schemaVersion(),
                store.getClass().getSimpleName(), policy, store.durable(),
                service.snapshotWarnChars());
        if (!store.durable()) {
            log.warn("默认检查点存储不具备跨进程存活能力，跨重启恢复将不可用；"
                    + "请声明自定义 CheckpointStore 实现（文件 / SQLite / JDBC）");
        }
    }

    /**
     * 内置功能开关：根据扫描到的 @Enable* 元数据，开启框架内部功能。<br>
     * 纯标记 Enable（无装配类）由框架内部在此感知。
     */
    private static void applyBuiltInEnables(TianGongScanner scanner, FrameworkContainer container) {
        boolean agentAsync = scanner.getEnableMetadata().stream()
                .anyMatch(meta -> meta.enableAnnotation() == EnableAsync.class && meta.enabled());
        container.setAsyncEnabled(agentAsync);
        boolean trace = scanner.getEnableMetadata().stream()
                .anyMatch(meta -> meta.enableAnnotation() == EnableTrace.class && meta.enabled());
        container.setTraceEnabled(trace);
        boolean autoConfiguration = scanner.getEnableMetadata().stream()
                .anyMatch(meta -> meta.enableAnnotation() == EnableAutoConfiguration.class && meta.enabled());
        container.setAutoConfigurationEnabled(autoConfiguration);
        boolean exceptionListener = scanner.getEnableMetadata().stream()
                .anyMatch(meta -> meta.enableAnnotation() == EnableExceptionListener.class && meta.enabled());
        container.setExceptionListenerEnabled(exceptionListener);
    }

    /**
     * 执行 @Enable* 装配：实例化 {@code value()} 中的装配类。<br>
     * 实现 {@link FrameworkConfiguration} 的类执行 configure；普通类注册为容器 Bean。
     */
    private static FrameworkConfiguratorImpl enableConfigure(TianGongScanner scanner, FrameworkContainer container,
                                        LlmRegistry llmRegistry, FunctionRegistry functionRegistry,
                                        TaskExecutorRegistry executorRegistry, ThreadContextRegistry threadContextRegistry,
                                        BeanDefinitionRegistry registry, Storage<?, ?> storage,
                                        AgentLoopExecutor agentLoopExecutor) {
        FrameworkConfiguratorImpl configurator = new FrameworkConfiguratorImpl(
                registry, scanner, container, llmRegistry, functionRegistry,
                executorRegistry, threadContextRegistry, storage, agentLoopExecutor);
        for (EnableMetadata metadata : scanner.getEnableMetadata()) {
            for (Class<?> configClass : metadata.configurations()) {
                try {
                    if (shouldRegister(configClass)) {
                        assemble(configurator, configClass);
                    }
                } catch (Exception e) {
                    throw new SystemRuntimeException(
                            "Enable 装配失败: " + metadata.enableAnnotation().getSimpleName()
                                    + " -> " + configClass.getName(), e);
                }
            }
        }
        // @EnableAutoConfiguration 开启 → @AutoConfiguration 配置类装配
        // 实现 FrameworkConfiguration → 调 configure()；否则注册为 Bean
        if (container.isAutoConfigurationEnabled()) {
            for (Class<?> configClass : scanner.getAutoConfigurations()) {
                if (shouldRegister(configClass)) {
                    try {
                        assemble(configurator, configClass);
                    } catch (Exception e) {
                        throw new SystemRuntimeException(
                                "AutoConfiguration 装配失败: " + configClass.getName(), e);
                    }
                }
            }
        }
        // @Import 声明式导入：实现 FrameworkConfiguration → configure；否则注册为 Bean
        for (ImportMetadata metadata : scanner.getImportMetadata()) {
            for (Class<?> importClass : metadata.imports()) {
                if (!shouldRegister(importClass)) {
                    continue;
                }
                try {
                    assemble(configurator, importClass);
                } catch (Exception e) {
                    throw new SystemRuntimeException(
                            "Import 装配失败: " + importClass.getName(), e);
                }
            }
        }
        return configurator;
    }

    /**
     * 装配一个类：实现 FrameworkConfiguration → configure；否则注册为 Bean
     */
    private static void assemble(FrameworkConfigurator configurator, Class<?> configClass) throws Exception {
        if (FrameworkConfiguration.class.isAssignableFrom(configClass)) {
            ((FrameworkConfiguration) configClass.getConstructor().newInstance())
                    .configure(configurator);
        } else {
            configurator.registerBean(configClass);
        }
    }

    /**
     * 条件装配检查：类标 {@code @ConditionalOnClass} 时，value 中所有类名必须存在（Class.forName）。
     */
    private static boolean shouldRegister(Class<?> configClass) {
        ConditionalOnClass condition = configClass.getAnnotation(ConditionalOnClass.class);
        if (condition == null) {
            return true;
        }
        for (String className : condition.value()) {
            try {
                Class.forName(className, false, configClass.getClassLoader());
            } catch (ClassNotFoundException e) {
                log.debug("条件装配不满足，跳过注册: {}（缺少类 {}）", configClass.getName(), className);
                return false;
            }
        }
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <ID, R extends StorageRecord<ID>> Storage<R, ID> getStorage() {
        return (Storage<R, ID>) storage;
    }

}
