package org.wutong.tiangong.spring.bridge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.util.StringUtils;
import org.wutong.tiangong.annotations.annotation.definition.BeanDefinition;
import org.wutong.tiangong.annotations.annotation.framework.Primary;
import org.wutong.tiangong.bytebuddy.DeferredInstancePlaceholder;
import org.wutong.tiangong.common.exception.SystemRuntimeException;
import org.wutong.tiangong.core.container.Container;
import org.wutong.tiangong.core.container.FrameworkContainer;
import org.wutong.tiangong.core.container.context.framework.ApplicationFrameworkContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 共享实例管理空间：两个容器"跨容器看到对方实例"的中枢。<br>
 * <br>
 * 职责（对应设计里的四件事）：
 * <ol>
 *     <li><b>归属登记</b>：记录每个类由哪个容器（或两个容器）创建；
 *         同一类被双侧管理（双实例、状态分裂）→ 启动期 WARN</li>
 *     <li><b>可见性规则</b>：{@code @TianGongWrite} 只在<b>天工</b>侧解析、
 *         {@code @SpringWrite} 只在<b>Spring</b>侧解析 —— 不跨界兜底（方法注释与实现都体现这条）</li>
 *     <li><b>延迟回填队列</b>：Spring Bean 先于天工自举创建，具体类依赖先写占位，
 *         此处登记注入点；天工就绪（{@link #onTianGongReady}）后统一定位真实例并回填</li>
 *     <li><b>服务端点视图</b>：{@link #serviceEndpoints()} 暴露已注册的服务地址
 *         （只读，便于诊断"为什么调用说未注册"）</li>
 * </ol>
 */
@Slf4j
public class SharedInstanceSpace {

    public static final String OWNER_SPRING = "spring";
    public static final String OWNER_TIANGONG = "tiangong";

    /**
     * 待回填的注入点：<br>
     * placeholder 为就绪前写入的占位（ByteBuddy 子类或 JDK 代理；无占位时为 null）。
     */
    public record PendingWrite(
            Object bean,
            String description,
            Class<?> type,
            String name,
            java.util.function.BiConsumer<Object, Object> writer,
            Object placeholder
    ) {
    }

    private final ApplicationContext springContext;

    private volatile ApplicationFrameworkContext tiangongContext;

    private final List<PendingWrite> pendingWrites = new CopyOnWriteArrayList<>();
    private final Map<Class<?>, Set<String>> owners = new ConcurrentHashMap<>();

    public SharedInstanceSpace(ApplicationContext springContext) {
        this.springContext = springContext;
    }

    // ==================== 生命周期 ====================

    /**
     * 天工自举完成：登记归属 → 回填延迟注入点。
     */
    public void onTianGongReady(ApplicationFrameworkContext context) {
        this.tiangongContext = context;
        registerOwnership();
        backfill();
        log.info("共享实例空间就绪：归属登记 {} 类（双容器冲突 {}），服务端点 {} 个",
                owners.size(), conflicts().size(), serviceEndpoints().size());
    }

    /**
     * 天工容器是否已就绪（未就绪时 {@code @TianGongWrite} 走占位 + 回填）。
     */
    public boolean isReady() {
        return tiangongContext != null;
    }

    // ==================== 延迟回填 ====================

    public void registerPendingWrite(PendingWrite write) {
        pendingWrites.add(write);
    }

    /**
     * 回填全部登记注入点：定位真实例 → 占位转发目标 → 写入字段/setter。
     *
     * @throws SystemRuntimeException 依赖解析失败（缺失 / 多候选无 @Primary）——启动期失败，
     *                                与 {@code @Inject} 的行为一致：不在运行期留半初始化对象
     */
    public void backfill() {
        if (pendingWrites.isEmpty()) {
            return;
        }
        for (PendingWrite write : pendingWrites) {
            Object instance = resolveTianGongInstance(write.type(), write.name(), write.description());
            if (write.placeholder() != null) {
                if (write.placeholder() instanceof java.lang.reflect.Proxy) {
                    DeferredJdkProxy.setTarget(write.placeholder(), instance);
                } else {
                    DeferredInstancePlaceholder.setTarget(write.placeholder(), instance);
                }
            }
            write.writer().accept(write.bean(), instance);
            log.info("@TianGongWrite 回填：{} <- {}（{}）", write.description(),
                    instance.getClass().getSimpleName(), write.type().getSimpleName());
        }
        int count = pendingWrites.size();
        pendingWrites.clear();
        log.info("@TianGongWrite 回填完成：{} 个注入点", count);
    }

    /**
     * 严格解析天工实例：名字优先；类型路径下 0 候选 / 多候选无 {@code @Primary} 都报错
     * （对齐 Spring 的解析语义，不静默取第一个）。
     *
     * @param where 注入点描述（报错用）
     */
    public Object resolveTianGongInstance(Class<?> type, String name, String where) {
        ApplicationFrameworkContext context = this.tiangongContext;
        if (context == null) {
            throw new SystemRuntimeException(
                    "天工尚未就绪，无法解析 @TianGongWrite 依赖: " + where);
        }
        Container container = context.getContainer();

        if (StringUtils.hasText(name)) {
            Object bean = container.getBean(name);
            if (bean == null) {
                throw new SystemRuntimeException(
                        "@TianGongWrite 依赖未找到: " + where + "，名字 [" + name + "] 在天工容器中不存在");
            }
            if (!type.isInstance(bean)) {
                throw new SystemRuntimeException(
                        "@TianGongWrite 依赖类型不符: " + where + "，名字 [" + name + "] 是 "
                                + bean.getClass().getName() + "，不是 " + type.getName());
            }
            return bean;
        }

        if (!(container instanceof FrameworkContainer framework)) {
            throw new SystemRuntimeException(
                    "@TianGongWrite 需要 FrameworkContainer 做严格候选解析，实际: " + container.getClass());
        }
        List<BeanDefinition> candidates = framework.candidatesOf(type);
        if (candidates.isEmpty()) {
            throw new SystemRuntimeException(
                    "@TianGongWrite 依赖未找到: " + where + "，天工容器中没有任何 " + type.getName() + " 的 Bean");
        }
        if (candidates.size() == 1) {
            return framework.getBean(candidates.get(0).getBeanName());
        }
        BeanDefinition primary = pickPrimary(candidates);
        if (primary == null) {
            throw new SystemRuntimeException(
                    "@TianGongWrite 依赖存在 " + candidates.size() + " 个候选 "
                            + candidates.stream().map(BeanDefinition::getBeanName).toList()
                            + "，必须用 @TianGongWrite(\"beanName\") 指定: " + where);
        }
        return framework.getBean(primary.getBeanName());
    }

    private static BeanDefinition pickPrimary(List<BeanDefinition> candidates) {
        BeanDefinition best = null;
        int bestOrder = Integer.MAX_VALUE;
        for (BeanDefinition candidate : candidates) {
            Primary primary = candidate.getBeanClass().getAnnotation(Primary.class);
            if (primary != null && primary.value() < bestOrder) {
                bestOrder = primary.value();
                best = candidate;
            }
        }
        return best;
    }

    // ==================== 归属登记 ====================

    private void registerOwnership() {
        ApplicationFrameworkContext context = this.tiangongContext;
        if (context != null && context.getContainer() instanceof FrameworkContainer framework) {
            for (BeanDefinition definition : framework.candidatesOf(Object.class)) {
                owners.computeIfAbsent(definition.getBeanClass(), k -> new LinkedHashSet<>())
                        .add(OWNER_TIANGONG);
            }
        }
        for (String beanName : springContext.getBeanDefinitionNames()) {
            Class<?> type;
            try {
                type = springContext.getType(beanName);
            } catch (Exception e) {
                continue;   // 个别定义无法解析类型（FactoryBean 等）：跳过，不影响冲突检测
            }
            if (type != null) {
                owners.computeIfAbsent(type, k -> new LinkedHashSet<>()).add(OWNER_SPRING);
            }
        }
        List<String> conflictNames = conflicts();
        if (!conflictNames.isEmpty()) {
            log.warn("检测到被双容器管理的类型（同一类存在两个实例、状态会分裂；"
                    + "请只保留一侧的组件注解，或改用 @TianGongWrite/@SpringWrite 显式桥接）：{}", conflictNames);
        }
    }

    /**
     * 双容器同时管理的类型名（启动期告警用；正常应用应为空）。
     */
    public List<String> conflicts() {
        List<String> conflicts = new ArrayList<>();
        owners.forEach((type, set) -> {
            if (set.size() > 1) {
                conflicts.add(type.getName() + set);
            }
        });
        return conflicts;
    }

    /**
     * 观测：某类型由哪些容器管理（无记录返回空集合）。
     */
    public Set<String> ownershipOf(Class<?> type) {
        return owners.getOrDefault(type, Set.of());
    }

    // ==================== 服务端点视图 ====================

    /**
     * 已注册的服务地址（只读；诊断"调用说未注册"时先看这里）。
     */
    public List<String> serviceEndpoints() {
        ApplicationFrameworkContext context = this.tiangongContext;
        return context == null ? List.of() : context.getServiceEntranceRegistry().urls();
    }
}
