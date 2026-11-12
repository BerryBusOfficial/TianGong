package com.taixu.tiangong.bytebuddy;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.This;
import net.bytebuddy.matcher.ElementMatchers;
import com.taixu.tiangong.common.exception.SystemRuntimeException;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * 延迟实例占位：为"真实实例尚未就绪、但字段必须立刻可写"的场景提供一个安全占位。<br>
 * <br>
 * <h2>语义</h2>
 * <ul>
 *     <li><b>不触发构造器</b>：用 {@code Unsafe.allocateInstance} 分配（不执行目标类构造器，
 *         不产生任何初始化副作用），因此目标类无需无参构造</li>
 *     <li><b>就绪前调用 → 明确报错</b>：抛 {@code SystemRuntimeException("…尚未就绪…")}，
 *         而不是把 NPE 留给调用方；toString/hashCode/equals 例外（返回安全值）</li>
 *     <li><b>就绪后转发</b>：{@link #setTarget} 之后所有方法调用转发到真实实例
 *         （调用方通常回填字段后直接替换占位，转发是兜底）</li>
 * </ul>
 * <br>
 * <h2>限制（对齐"接口优先"的设计建议）</h2>
 * <ul>
 *     <li>final 类无法代理 → {@link #isSupported} 返回 false（调用方应启动期报错，
 *         提示改用接口注入）</li>
 *     <li>final 方法无法拦截（{@link #finalMethods} 可供调用方告警）</li>
 * </ul>
 */
public final class DeferredInstancePlaceholder {

    /** 目标字段名（生成类内部） */
    private static final String TARGET_FIELD = "__tiangongDeferredTarget";

    private static final ByteBuddy BYTE_BUDDY = new ByteBuddy();

    /** 每个原始类 → 增强子类（同一类型共享一个增强类） */
    private static final ClassValue<Class<?>> ENHANCED = new ClassValue<>() {
        @Override
        protected Class<?> computeValue(Class<?> type) {
            return enhance(type);
        }
    };

    /** 每个增强类 → 目标字段 */
    private static final ClassValue<Field> TARGET_FIELDS = new ClassValue<>() {
        @Override
        protected Field computeValue(Class<?> type) {
            try {
                Field field = type.getDeclaredField(TARGET_FIELD);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                throw new IllegalStateException("占位类缺少目标字段: " + type.getName(), e);
            }
        }
    };

    /** sun.misc.Unsafe（反射获取；JDK21 实测可用） */
    private static final Object UNSAFE;
    private static final Method ALLOCATE_INSTANCE;

    static {
        Object unsafe = null;
        Method allocate = null;
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            unsafe = theUnsafe.get(null);
            allocate = unsafeClass.getMethod("allocateInstance", Class.class);
        } catch (Throwable ignored) {
            // 环境不支持：create() 时给出明确报错
        }
        UNSAFE = unsafe;
        ALLOCATE_INSTANCE = allocate;
    }

    private DeferredInstancePlaceholder() {
    }

    /**
     * 类型是否支持占位：非接口、非 final、非枚举/数组/基本类型。
     */
    public static boolean isSupported(Class<?> type) {
        return type != null
                && !type.isInterface()
                && !type.isEnum()
                && !type.isArray()
                && !type.isPrimitive()
                && !Modifier.isFinal(type.getModifiers());
    }

    /**
     * 不支持时的原因（用于报错信息）。
     */
    public static String unsupportedReason(Class<?> type) {
        if (type == null) {
            return "类型为 null";
        }
        if (type.isInterface()) {
            return "接口应走代理路径（JDK 代理），而不是 ByteBuddy 占位";
        }
        if (Modifier.isFinal(type.getModifiers())) {
            return "final 类无法被继承代理";
        }
        return "枚举/数组/基本类型不支持";
    }

    /**
     * 列出声明的 final 方法（无法拦截；调用方宜告警）。
     */
    public static List<String> finalMethods(Class<?> type) {
        List<String> finals = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            if (Modifier.isFinal(method.getModifiers()) && !Modifier.isStatic(method.getModifiers())) {
                finals.add(method.getName());
            }
        }
        return finals;
    }

    /**
     * 创建占位实例（不执行目标类构造器）。<br>
     * 就绪前调用业务方法会抛明确异常；{@link #setTarget} 后转发到真实实例。
     *
     * @throws SystemRuntimeException 类型不支持，或当前 JVM 不支持无构造实例化
     */
    public static Object create(Class<?> type) {
        if (!isSupported(type)) {
            throw new SystemRuntimeException(
                    "无法创建延迟占位实例 [" + type.getName() + "]：" + unsupportedReason(type));
        }
        if (UNSAFE == null) {
            throw new SystemRuntimeException(
                    "无法创建延迟占位实例 [" + type.getName() + "]：当前 JVM 不支持无构造实例化"
                            + "（sun.misc.Unsafe 不可用）。请改用接口注入，或给目标类提供无参构造方案");
        }
        Class<?> enhanced = ENHANCED.get(type);
        try {
            return ALLOCATE_INSTANCE.invoke(UNSAFE, enhanced);
        } catch (Exception e) {
            throw new SystemRuntimeException(
                    "无法创建延迟占位实例 [" + type.getName() + "]: " + e.getMessage(), e);
        }
    }

    /**
     * 设置/替换真实目标；之后方法调用转发到它。
     */
    public static void setTarget(Object placeholder, Object target) {
        try {
            TARGET_FIELDS.get(placeholder.getClass()).set(placeholder, target);
        } catch (IllegalAccessException e) {
            throw new SystemRuntimeException("无法设置占位实例的目标: " + placeholder.getClass().getName(), e);
        }
    }

    /**
     * 读取当前目标（未设置时为 null）。
     */
    public static Object targetOf(Object placeholder) {
        try {
            return TARGET_FIELDS.get(placeholder.getClass()).get(placeholder);
        } catch (IllegalAccessException e) {
            throw new SystemRuntimeException("无法读取占位实例的目标: " + placeholder.getClass().getName(), e);
        }
    }

    // ==================== 内部 ====================

    private static Class<?> enhance(Class<?> type) {
        return BYTE_BUDDY.subclass(type)
                .defineField(TARGET_FIELD, Object.class, Modifier.PRIVATE | Modifier.VOLATILE)
                .method(ElementMatchers.any().and(ElementMatchers.not(ElementMatchers.isConstructor())))
                .intercept(MethodDelegation.to(Dispatcher.class))
                .make()
                .load(type.getClassLoader(), ClassLoadingStrategy.Default.INJECTION)
                .getLoaded();
    }

    /**
     * 方法拦截器：未就绪 → 明确报错（Object 方法给安全值）；就绪 → 转发真实实例。
     */
    public static class Dispatcher {

        @RuntimeType
        public static Object intercept(@This Object proxy, @Origin Method method,
                                       @AllArguments Object[] args) throws Throwable {
            Object target = targetOf(proxy);
            if (target == null) {
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "toString" -> "DeferredPlaceholder[" + proxy.getClass().getSuperclass().getSimpleName() + "]";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
                        default -> null;
                    };
                }
                throw new SystemRuntimeException(
                        "组件尚未就绪：占位实例被调用 [" + method.getName() + "]（"
                                + proxy.getClass().getSuperclass().getSimpleName()
                                + "）—— 真实实例尚未回填（宿主自举未完成，或依赖解析失败）");
            }
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause() == null ? e : e.getCause();
            }
        }
    }
}
