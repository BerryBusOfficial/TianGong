package com.taixu.tiangong.core.agent;

import com.taixu.tiangong.annotations.annotation.framework.agent.interfaces.StartCondition;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.NodeType;
import com.taixu.tiangong.annotations.annotation.framework.agent.loop.ReplayPolicy;
import com.taixu.tiangong.common.agent.context.AgentContext;
import com.taixu.tiangong.common.sandbox.CommandLine;
import com.taixu.tiangong.common.sandbox.ExecOptions;
import com.taixu.tiangong.common.sandbox.Sandbox;
import com.taixu.tiangong.common.util.JsonUtil;
import com.taixu.tiangong.core.config.ConfigHolder;
import com.taixu.tiangong.core.container.Container;
import com.taixu.tiangong.core.container.context.ContextHolder;
import com.taixu.tiangong.core.sandbox.SandboxRegistry;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * AgentNode 节点定义：节点名/类型/跳转路由/起始条件/处理器方法。
 */
public class AgentNodeDefinition {

    private final String name;
    private final NodeType type;
    private final String[] next;
    private final Class<? extends StartCondition> startConditionClass;
    private final ReplayPolicy replayPolicy;
    private final Object target;
    private final Method method;

    public AgentNodeDefinition(String name, NodeType type, String[] next,
                               Class<? extends StartCondition> startConditionClass,
                               Object target, Method method) {
        this(name, type, next, startConditionClass, ReplayPolicy.ALWAYS, target, method);
    }

    public AgentNodeDefinition(String name, NodeType type, String[] next,
                               Class<? extends StartCondition> startConditionClass,
                               ReplayPolicy replayPolicy,
                               Object target, Method method) {
        this.name = name;
        this.type = type;
        this.next = next;
        this.startConditionClass = startConditionClass;
        this.replayPolicy = replayPolicy != null ? replayPolicy : ReplayPolicy.ALWAYS;
        this.target = target;
        this.method = method;
        method.setAccessible(true);
    }

    public String name() {
        return name;
    }

    public NodeType type() {
        return type;
    }

    public String[] next() {
        return next;
    }

    /**
     * 崩溃恢复时的可重放性声明（检查点契约 A5）。<br>
     * 取自 {@code @AgentNode(replay)}，默认 {@link ReplayPolicy#ALWAYS}。
     */
    public ReplayPolicy replayPolicy() {
        return replayPolicy;
    }

    /** 是否允许在崩溃恢复时补跑本节点 */
    public boolean replayable() {
        return replayPolicy == ReplayPolicy.ALWAYS;
    }

    public Object target() {
        return target;
    }

    public Method method() {
        return method;
    }

    /** 起始条件类（供 StartTriggerSource 和 AgentRuntime 读取） */
    public Class<? extends StartCondition> startConditionClass() {
        return startConditionClass;
    }

    /**
     * 起始条件：未指定用 DEFAULT（恒 true）。<br>
 * 若指定了具体类，优先从容器获取 Bean（支持 @Inject 依赖注入），
     * 兜底反射实例化（需无参构造）。
     *
     * @param container 框架容器，可为 null（测试环境无容器时反射实例化）
     */
    public StartCondition resolveStartCondition(Container container) {
        if (startConditionClass == null || startConditionClass == StartCondition.class) {
            return StartCondition.DEFAULT;
        }
        // 优先从容器获取 Bean
        if (container != null) {
            StartCondition bean = container.getBean(startConditionClass);
            if (bean != null) {
                return bean;
            }
        }
        // 兜底：反射实例化
        return newInstance(startConditionClass);
    }

    /**
     * @deprecated 使用 {@link #resolveStartCondition(Container)}，StartCondition 实现类改为 Bean
     */
    @Deprecated
    public StartCondition resolveStartCondition() {
        if (startConditionClass == null || startConditionClass == StartCondition.class) {
            return StartCondition.DEFAULT;
        }
        return newInstance(startConditionClass);
    }

    private <T> T newInstance(Class<T> clazz) {
        try {
            return clazz.getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "无法实例化节点条件类 [" + clazz.getName() + "]，需要无参构造器", e);
        }
    }

    /**
     * 调用节点方法。<br>
     * 参数约定：无参，或一个 AgentContext 兼容参数（框架传入当前 context）；<br>
     * 返回约定：<br>
     * - BRANCH：String（下一个节点名）<br>
     * - 其他类型：void → context 不变；AgentContext 子类型 → 作为更新后的 context
     */
    /**
     * 调用节点方法。<br>
     * <br>
     * <h2>参数绑定规则（按形参类型逐个解析）</h2>
     * <ol>
     *     <li>{@code AgentContext} 及其子类 → 传入当前上下文。<b>最多一个</b>，
     *         多个会报错（无法判断哪个是主上下文）</li>
     *     <li>容器中的 Bean → 从容器取。<b>这是用户无需 {@code @Inject} 的原因</b>：
     *         {@link AgentToolInvoker} 等框架类型直接声明成形参即可收到</li>
     *     <li>其余类型 → {@code null}（沿用既有兜底语义）</li>
     * </ol>
     * 规则 2 是<b>通用</b>的，不限于某个节点类型：START / BRANCH / COMMON 的节点方法
     * 同样可以声明 {@code AgentToolInvoker} 形参。{@code NodeType.TOOL} 只是给
     * 「这个节点要调工具」加一层语义标记与启动期校验，注入能力本身不依赖它。
     */
    public Object invoke(Object context) {
        try {
            Parameter[] parameters = method.getParameters();
            if (parameters.length == 0) {
                return method.invoke(target);
            }
            return method.invoke(target, bindParameters(context));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("节点方法不可访问: " + method.getName(), e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("节点方法执行失败: " + method.getName(), cause);
        }
    }

    /**
     * 按形参类型绑定实参。<br>
     * <br>
     * 用 {@code isInstance} 而非 {@code ==} 判断上下文：用户的会话上下文通常是
     * {@code AgentContext} 的子类（如 {@code SessionContext}），精确判等会注入成 null。
     */
    private Object[] bindParameters(Object context) {
        Parameter[] parameters = method.getParameters();
        Object[] args = new Object[parameters.length];
        int contextCount = 0;
        for (int i = 0; i < parameters.length; i++) {
            Class<?> type = parameters[i].getType();
            if (context != null && type.isInstance(context)) {
                if (++contextCount > 1) {
                    throw new IllegalStateException(
                            "节点方法声明了多个 AgentContext 参数，无法确定主上下文: " + method.getName());
                }
                args[i] = context;
            } else {
                args[i] = beanOrNull(type);
            }
        }
        return args;
    }

    /**
     * 尝试从容器取该类型的 Bean，取不到返回 {@code null}。<br>
     * <br>
     * 容器不可用时（如单测未启动上下文）同样返回 {@code null}，不抛异常 ——
     * 节点方法的可选参数本就允许为 null，强行报错会让无容器的场景无法构造节点。
     */
    private Object beanOrNull(Class<?> type) {
        var application = ContextHolder.getApplication();
        if (application == null) {
            return null;
        }
        Container container = application.getContainer();
        return container == null ? null : container.getBean(type);
    }

    /**
     * 调用 MERGE 节点方法，传入分支上下文列表。<br>
     * 参数约定：第一个参数为 AgentContext（主上下文），可选第二个 List 参数接收分支结果。
     */
    @SuppressWarnings("unchecked")
    public Object invokeMerge(Object context, List<AgentContext> branches) {
        try {
            Parameter[] parameters = method.getParameters();
            Object[] args = new Object[parameters.length];
            for (int i = 0; i < parameters.length; i++) {
                Class<?> type = parameters[i].getType();
                if (AgentContext.class.isAssignableFrom(type) && type.isInstance(context)) {
                    args[i] = context;
                } else if (List.class.isAssignableFrom(type)) {
                    args[i] = branches;
                } else {
                    args[i] = null;
                }
            }
            return method.invoke(target, args);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("MERGE 节点方法不可访问: " + method.getName(), e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("MERGE 节点方法执行失败: " + method.getName(), cause);
        }
    }

    /**
     * 调用 COMMAND 节点方法（命令执行契约）。<br>
     * <br>
     * <h2>参数绑定按<b>类型</b>匹配，顺序任意</h2>
     * <ul>
     *     <li>{@link CommandLine} → 注入待执行命令（由
     *         {@code CommandNodeProcessor} 在注册期保证有且仅有一个）</li>
     *     <li>{@code AgentContext} 或其子类 → 注入工作流上下文</li>
     *     <li>{@link Sandbox} → 注入 {@code @Sandbox(box=...)} 指定的实例；
     *         未标注该注解时注入 {@link SandboxRegistry#defaultSandbox()}</li>
     * </ul>
     *
     * <h2>为什么必须是新增方法，不能复用 {@link #invoke(Object)}</h2>
     * {@code invoke()} 的契约是「最多 1 个 AgentContext 参数」，参数多于此直接抛
     * {@code IllegalStateException}。COMMAND 节点最少需要 {@code CommandLine}，
     * 通常还需要 {@code Sandbox}，两个参数必然超界。
     *
     * <h2>沙箱实例由注册表持有</h2>
     * 本方法<b>不自己 new 沙箱</b>，一律走 {@link SandboxRegistry}：
     * 保证同类沙箱在多个节点间是同一个实例（连接池/镜像缓存才有意义），
     * 且能在 shutdown 时被统一 {@code close()}。
     *
     * @param context 工作流上下文
     * @param cmd     待执行命令
     * @return 节点方法的返回值（void 时为 {@code null}）
     */
    public Object invokeCommand(Object context, CommandLine cmd) {
        // @Sandbox 注解与 Sandbox 接口同名（不同模块、不同包），此处必须用全限定名引用注解；
        // 用 var 承接以避免在局部变量声明处重复这一长串
        var ann = method.getAnnotation(
                com.taixu.tiangong.annotations.annotation.framework.agent.loop.command.Sandbox.class);
        SandboxRegistry registry = ContextHolder.getApplication().getSandboxRegistry();
        Sandbox box = ann != null ? registry.getOrCreate(ann.box()) : registry.defaultSandbox();

        Object[] args = bindCommandArgs(context, cmd, box);

        try {
            return method.invoke(target, args);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("COMMAND 节点方法不可访问: " + method.getName(), e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("COMMAND 节点方法执行失败: " + method.getName(), cause);
        }
    }


    /**
     * 按类型把 {@code context / cmd / box} 绑到方法形参上。<br>
     * <br>
     * 用 {@code isInstance} 而非 {@code ==} 判断 AgentContext：用户的会话上下文
     * 通常是 {@code AgentContext} 的子类（如 {@code SessionContext}），
     * 精确判等会让它们注入成 {@code null}。
     */
    private Object[] bindCommandArgs(Object context, CommandLine cmd, Sandbox box) {
        Parameter[] parameters = method.getParameters();
        Object[] args = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Class<?> type = parameters[i].getType();
            if (CommandLine.class.isAssignableFrom(type)) {
                args[i] = cmd;
            } else if (type.isInstance(context)) {
                args[i] = context;
            } else if (Sandbox.class.isAssignableFrom(type)) {
                args[i] = box;
            } else {
                // CommandNodeProcessor 在注册期已挡掉不受支持的类型，
                // 走到这里说明两种校验不一致 —— 属于框架内部错误
                throw new IllegalStateException(
                        "COMMAND 节点 [" + method.getName() + "] 参数 [" + type.getSimpleName()
                                + "] 无法绑定；与注册期签名校验不一致");
            }
        }
        return args;
    }

    /**
     * 解析本次执行的超时，按优先级从高到低覆盖。<br>
     * <br>
     * <ol>
     *     <li>{@code ExecOptions#timeout()} —— 单次调用（最高）</li>
     *     <li>{@code @Sandbox(timeoutMs)} —— 节点级，非 -1 才生效</li>
     *     <li>全局配置 {@code sandbox.<name>.timeout} —— 运维可调</li>
     *     <li>{@code Sandbox#defaultTimeout()} —— 实现默认（兜底）</li>
     * </ol>
     *
     * <h2>为什么由这里解析，而不是交给沙箱实现</h2>
     * 若每个 {@code Sandbox} 实现都要重复这套合并逻辑，用户写 {@code DockerSandbox}
     * 时就很可能写漏某一层 —— 而这类错误<b>很难被发现</b>（多半表现为「超时莫名其妙不准」）。
     * 集中在此处，保证所有沙箱行为一致。
     *
     * <h2>为什么用 {@code -1} 表达「未设置」</h2>
     * 要区分「用户没写」与「用户就想要这个值」：前者跟随下层变化，后者不跟随。
     * 与 {@code AgentNode#replay()} 的默认值策略是同一种语言。
     */
    public ExecOptions resolveExecOptions(Sandbox box, ExecOptions singleCall) {
        var ann = method.getAnnotation(
                com.taixu.tiangong.annotations.annotation.framework.agent.loop.command.Sandbox.class);
        long timeoutMs = -1L;
        if (singleCall != null && singleCall.timeout() != null) {
            timeoutMs = singleCall.timeout().toMillis();
        } else if (ann != null && ann.timeoutMs() >= 0) {
            timeoutMs = ann.timeoutMs();
        } else {
            Duration global = ConfigHolder.getValue(
                    "sandbox." + box.name() + ".timeout", Duration.class);
            if (global != null && !global.isNegative() && !global.isZero()) {
                timeoutMs = global.toMillis();
            }
        }
        return ExecOptions.builder().timeoutMillis(timeoutMs).build();
    }

    /** 方法返回是否为 AgentContext（更新 context） */
    public boolean returnsContext() {
        return AgentContext.class.isAssignableFrom(method.getReturnType());
    }

    /** 方法返回是否为 String（BRANCH 决策） */
    public boolean returnsString() {
        return String.class == method.getReturnType();
    }

    /**
     * 调用 ENTRANCE 节点方法（HTTP 语义）：第一个参数为 AgentContext，后续参数从 JSON body 反序列化。<br>
     * <ul>
     *     <li>0 个非 AgentContext 参数：不反序列化</li>
     *     <li>1 个非 AgentContext 参数：整个 body 反序列化为该类型</li>
     *     <li>多个：body 转 Map，按参数名提取</li>
     * </ul>
     */
    public Object invokeEntrance(Object context, String jsonBody) {
        return invokeEntrance(context, jsonBody, true);
    }

    /**
     * 调用 ENTRANCE 节点方法，第一个参数为 AgentContext，后续参数从 body 绑定。<br>
     * <br>
     * {@code jsonBody = true}（HTTP ENTRANCE 契约）：body 一律按 JSON 解析。<br>
     * {@code jsonBody = false}（STDIN 输入契约）：用户输入是原始文本，
     * 参数类型为 {@link String} 时直接注入原文，不做 JSON 解析；
     * 参数为其他类型时仍按 JSON 解析；多参数签名要求输入为 JSON 对象文本。
     *
     * @param context  节点上下文
     * @param body     参数字面量
     * @param jsonBody body 是否为 JSON 文本
     */
    public Object invokeEntrance(Object context, String body, boolean jsonBody) {
        try {
            Parameter[] parameters = method.getParameters();
            Object[] args = new Object[parameters.length];

            // 收集非 AgentContext 参数
            java.util.List<int[]> nonContextIndices = new java.util.ArrayList<>();
            for (int i = 0; i < parameters.length; i++) {
                Class<?> type = parameters[i].getType();
                if (AgentContext.class.isAssignableFrom(type)) {
                    args[i] = context;
                } else {
                    nonContextIndices.add(new int[]{i});
                }
            }

            if (!nonContextIndices.isEmpty() && body != null && !body.isEmpty()) {
                if (nonContextIndices.size() == 1) {
                    int idx = nonContextIndices.get(0)[0];
                    Class<?> paramType = parameters[idx].getType();
                    if (!jsonBody && paramType == String.class) {
                        // STDIN：原始文本直接注入，不走 JSON 解析
                        args[idx] = body;
                    } else {
                        // 入口 body 走宽容解析：camelCase 与 snake_case 都认。
                        // 旧实现直接用全局 JsonUtil（SNAKE_CASE），客户端按 Java 字段名
                        // 发 repoPath 会被静默忽略成 null —— 接口 500 且没有任何日志。
                        args[idx] = JsonUtil.fromBody(body, paramType);
                    }
                } else {
                    Map<String, Object> bodyMap = toMap(body, jsonBody);
                    for (int[] entry : nonContextIndices) {
                        int idx = entry[0];
                        String paramName = parameters[idx].getName();
                        Object value = bodyMap.get(paramName);
                        if (value != null) {
                            args[idx] = JsonUtil.convertToBean(value, parameters[idx].getType());
                        }
                    }
                }
            }

            return method.invoke(target, args);
        } catch (Exception e) {
            Throwable cause = (e instanceof InvocationTargetException ite) ? ite.getCause() : e;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("ENTRANCE 节点方法执行失败: " + method.getName(), cause);
        }
    }

    /**
     * body 转 Map（多参数签名）。
     *
     * @param jsonBody body 是否为 JSON 文本；false 且解析失败时给出 STDIN 专用提示
     */
    private Map<String, Object> toMap(String body, boolean jsonBody) throws Exception {
        try {
            return JsonUtil.bodyToMap(body);
        } catch (Exception e) {
            if (!jsonBody) {
                throw new IllegalStateException(
                        "STDIN 输入无法绑定到多参数方法 [" + method.getName()
                                + "]，请输入 JSON 对象文本", e);
            }
            throw e;
        }
    }

    @Override
    public String toString() {
        return "AgentNode[" + type + " " + name + "]";
    }
}
