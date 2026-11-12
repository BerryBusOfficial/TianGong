package com.taixu.tiangong.core.sandbox;

import lombok.extern.slf4j.Slf4j;
import com.taixu.tiangong.common.exception.SystemRuntimeException;
import com.taixu.tiangong.common.sandbox.Sandbox;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 沙箱注册表：按名 / 按类型解析实例，并统一持有其生命周期。<br>
 * <br>
 * <h2>为什么由注册表持有，而不是节点方法里 {@code new}</h2>
 * <ul>
 *     <li><b>单例语义</b>：同一个 {@code DockerSandbox} 被多个节点引用时应共用实例
 *         （连接池 / 镜像缓存才有意义），而不是每个节点各起一套</li>
 *     <li><b>关闭时机</b>：只有集中持有，框架 shutdown 时才能遍历调用
 *         {@link Sandbox#close()}。用户自行 {@code new} 的生命周期会脱离管理，
 *         造成资源泄漏</li>
 *     <li><b>可按名查找</b>：用户可在节点方法里 {@code find("docker")}
 *         实现「按命令动态选沙箱」的策略</li>
 * </ul>
 *
 * <h2>实例来源：容器优先，反射兜底</h2>
 * 解析顺序由 {@link #setInstanceSupplier(Function)} 决定，框架在容器 refresh 之后
 * 装配为「<b>先查容器 Bean，取不到才反射 new</b>」——与 {@code CheckpointStore}、
 * {@code ContextSnapshotter} 等既有扩展点同一套规则。<br>
 * <br>
 * 这一步不是可选优化：沙箱实现里 {@code @Inject} 一个 Docker 客户端或配置 Bean 是很自然的
 * 写法，若走反射 new，这些字段会<b>静默为 null</b>，直到真正执行才炸出 NPE。
 * 未装配供给器时（如单测直接 new 本类）退化为纯反射，行为不变。
 *
 * <h2>约定</h2>
 * <ul>
 *     <li>走反射兜底时，实现类必须有<b>公开无参构造器</b></li>
 *     <li>key 取自 {@link Sandbox#name()} 实例方法，因此按类型解析时
 *         <b>必然会先取得一个实例</b>才能拿到名字；真正的复用发生在之后</li>
 *     <li>同一名字重复注册时<b>抛异常</b> —— 静默覆盖会让「到底用的是哪个沙箱」变得难以判断</li>
 * </ul>
 */
@Slf4j
public class SandboxRegistry {

    /** 注册进容器时的 Bean 名：脚本运行时等组件需要在沙箱里执行第三方代码 */
    public static final String BEAN_NAME = "sandboxRegistry";

    private final Map<String, Sandbox> byName = new ConcurrentHashMap<>();

    /**
     * 命令解析器：从上下文取「本次要执行的命令」。<br>
     * 缺省为 {@code null} —— {@code AgentContext} 是纯抽象接口、不含命令字段，
     * 框架<b>无法</b>猜测应用把命令存在哪里。未设置时命令节点会在执行期报明确错误。<br>
     * <br>
     * 由框架在容器 refresh 之后从 Bean 中查找并注入（见
     * {@code ApplicationFrameworkContext#applySandboxExtensions}）；
     * 也可由应用手工调用 {@link #setCommandLineResolver} 覆盖。
     */
    private volatile CommandLineResolver commandLineResolver;

    /**
     * 沙箱实例供给器。默认纯反射；框架装配后改为「容器优先 + 反射兜底」。
     */
    private volatile Function<Class<? extends Sandbox>, Sandbox> instanceSupplier = this::instantiate;

    /**
     * 初始化即注册默认的本地沙箱。<br>
     * 未标注 {@code @Sandbox} 的命令节点使用这个：<b>本机直接执行、无隔离</b>。
     */
    public SandboxRegistry() {
        register(new LocalSandbox());
    }

    /**
     * 设置命令解析器。
     *
     * @param resolver 解析器；传 {@code null} 表示清除
     */
    public void setCommandLineResolver(CommandLineResolver resolver) {
        this.commandLineResolver = resolver;
        log.debug("命令解析器已设置: {}",
                resolver == null ? "无" : resolver.getClass().getSimpleName());
    }

    /**
     * 当前命令解析器。
     *
     * @return 未设置时为空 —— 调用方须显式处理，不可假定存在
     */
    public Optional<CommandLineResolver> commandLineResolver() {
        return Optional.ofNullable(commandLineResolver);
    }

    /**
     * 设置沙箱实例供给器。<br>
     * 框架在容器就绪后调用，使 {@code @Sandbox(box = Xxx.class)} 解析时优先拿到
     * 容器管理的 Bean（保住依赖注入）。<br>
     * <br>
     * 传入的实现<b>必须能自行兜底</b>：目标类型不是 Bean 时不能返回 {@code null}，
     * 否则 {@link #getOrCreate} 会拿不到名字而无法注册。
     *
     * @param supplier 供给器；传 {@code null} 恢复为纯反射
     */
    public void setInstanceSupplier(Function<Class<? extends Sandbox>, Sandbox> supplier) {
        boolean fallback = supplier == null;
        this.instanceSupplier = fallback ? this::instantiate : supplier;
        log.debug("沙箱实例供给器已设置: {}", fallback ? "反射兜底（默认）" : "容器优先");
    }

    /**
     * 注册一个沙箱实例。
     *
     * @throws SystemRuntimeException 同名沙箱已存在
     */
    public void register(Sandbox sandbox) {
        Sandbox previous = byName.putIfAbsent(sandbox.name(), sandbox);
        if (previous != null) {
            throw new SystemRuntimeException(
                    "重复注册沙箱: [%s] 已绑定 %s，本次尝试绑定 %s".formatted(
                            sandbox.name(),
                            previous.getClass().getName(),
                            sandbox.getClass().getName()));
        }
        log.debug("注册沙箱: {} -> {}", sandbox.name(), sandbox.getClass().getSimpleName());
    }

    /**
     * 按类型取得实例。<br>
     * 幂等：同一类型（同名）多次调用返回同一实例 —— 已存在就直接返回，不再新建。
     */
    public Sandbox getOrCreate(Class<? extends Sandbox> type) {
        Sandbox probe = instanceSupplier.apply(type);
        if (probe == null) {
            throw new SystemRuntimeException(
                    "沙箱实例供给器对 [%s] 返回了 null；供给器必须能自行兜底".formatted(type.getName()));
        }
        Sandbox existing = byName.putIfAbsent(probe.name(), probe);
        if (existing != null) {
            return existing;
        }
        log.debug("首次创建沙箱实例: {} -> {}", probe.name(), type.getSimpleName());
        return probe;
    }

    /**
     * 按名查找。
     *
     * @return 未注册时为空
     */
    public Optional<Sandbox> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /**
     * 默认沙箱：本地直执行的 {@link LocalSandbox}。
     */
    public Sandbox defaultSandbox() {
        return find(LocalSandbox.NAME)
                .orElseGet(() -> getOrCreate(LocalSandbox.class));
    }

    /** 全部已注册的沙箱 */
    public Collection<Sandbox> values() {
        return byName.values();
    }

    public int size() {
        return byName.size();
    }

    /**
     * 关闭全部沙箱。<br>
     * 幂等且容错：单个沙箱关闭失败不影响其余 ——
     * {@code close()} 是释放资源的最后机会，不该因为一个失败而放弃其他。
     */
    public void closeAll() {
        int count = byName.size();
        for (Sandbox sandbox : byName.values()) {
            try {
                sandbox.close();
            } catch (RuntimeException e) {
                log.warn("关闭沙箱 [{}] 失败: {}", sandbox.name(), e.getMessage());
            }
        }
        log.debug("已尝试关闭 {} 个沙箱实例", count);
    }

    /**
     * 反射实例化兜底：要求公开无参构造器。<br>
     * <br>
     * 公开是为了让外部的供给器（框架装配的「容器优先」实现）在未命中 Bean 时
     * 能复用这段逻辑，而不是复制一份。
     */
    public Sandbox instantiate(Class<? extends Sandbox> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new SystemRuntimeException(
                    "无法实例化沙箱 [%s]：需要公开无参构造器".formatted(type.getName()), e);
        }
    }
}
