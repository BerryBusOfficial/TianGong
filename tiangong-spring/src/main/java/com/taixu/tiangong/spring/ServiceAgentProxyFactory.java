package com.taixu.tiangong.spring;

import com.fasterxml.jackson.databind.JavaType;
import com.taixu.tiangong.spring.annotation.ServiceAgent;
import lombok.extern.slf4j.Slf4j;
import com.taixu.tiangong.common.util.JsonUtil;
import com.taixu.tiangong.core.api.entrance.EntranceResponse;
import com.taixu.tiangong.core.api.entrance.ServiceEntranceException;
import com.taixu.tiangong.core.api.entrance.ServiceEntranceRegistry;
import com.taixu.tiangong.spring.annotation.EntranceCall;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.function.Supplier;

/**
 * {@code @ServiceAgent} 契约的 JDK 代理：<br>
 * 方法调用 → 参数 JSON 化 → {@link ServiceEntranceRegistry} 投递 → 按返回类型反序列化；
 * 业务异常按<b>原始类型</b> rethrow（不走包装，受检异常走 sneaky throw 保真）。<br>
 * <br>
 * 代理本身是无状态的（注册表在<b>调用时</b>经 {@link TianGongRuntime} 惰性解析），
 * 所以可以在天工自举之前就注册进 Spring 容器 —— 调用时若未就绪会得到明确异常。
 */
@Slf4j
public final class ServiceAgentProxyFactory {

    private ServiceAgentProxyFactory() {
    }

    /**
     * 创建代理。
     *
     * @param contract 契约接口
     * @param runtime  天工运行时（惰性解析；调用时取注册表与超时）
     */
    public static Object create(Class<?> contract, Supplier<TianGongRuntime> runtime) {
        return Proxy.newProxyInstance(
                contract.getClassLoader(),
                new Class<?>[]{contract},
                new Handler(contract, runtime));
    }

    /**
     * 判定契约接口：直接标注 {@code @ServiceAgent}，或标注了以它作为元注解的自定义注解（可组合）。
     */
    public static boolean isServiceAgentContract(Class<?> candidate) {
        if (candidate == null || !candidate.isInterface()) {
            return false;
        }
        if (candidate.getAnnotation(ServiceAgent.class) != null) {
            return true;
        }
        for (java.lang.annotation.Annotation annotation : candidate.getAnnotations()) {
            if (annotation.annotationType()
                    .isAnnotationPresent(ServiceAgent.class)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 启动期校验契约接口：契约错误在这里就报出来，不拖到调用期。<br>
     * 由 {@link ServiceAgentRegistrar} 在注册代理前调用。
     */
    public static void validate(Class<?> contract) {
        if (!contract.isInterface()) {
            throw new ServiceEntranceException("@ServiceAgent 只能标在接口上: " + contract.getName());
        }
        for (Method method : contract.getDeclaredMethods()) {
            if (method.isDefault() || Modifier.isStatic(method.getModifiers())) {
                continue;   // default/static 方法不走代理投递
            }
            EntranceCall call = method.getAnnotation(EntranceCall.class);
            if (call == null) {
                throw new ServiceEntranceException(
                        "@ServiceAgent 契约 [" + contract.getName() + "] 的方法 [" + method.getName()
                                + "] 缺少 @EntranceCall(url=...)");
            }
            if (call.url() == null || call.url().isBlank()) {
                throw new ServiceEntranceException(
                        "@ServiceAgent 契约 [" + contract.getName() + "] 的方法 [" + method.getName()
                                + "] 的 @EntranceCall.url 不能为空");
            }
            if (!call.url().startsWith("agent://")) {
                throw new ServiceEntranceException(
                        "@ServiceAgent 契约 [" + contract.getName() + "] 的方法 [" + method.getName()
                                + "] 的地址 [" + call.url() + "] 暂不支持：当前仅支持 agent:// 同进程寻址");
            }
            if (method.getParameterCount() > 1) {
                throw new ServiceEntranceException(
                        "@ServiceAgent 契约 [" + contract.getName() + "] 的方法 [" + method.getName()
                                + "] 有 " + method.getParameterCount() + " 个参数："
                                + "契约约定至多一个参数（建议单一 DTO），多参数请合并为请求对象");
            }
        }
    }

    // ==================== 调用处理 ====================

    private static final class Handler implements InvocationHandler {

        private final Class<?> contract;
        private final Supplier<TianGongRuntime> runtime;

        private Handler(Class<?> contract, Supplier<TianGongRuntime> runtime) {
            this.contract = contract;
            this.runtime = runtime;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // Object 方法：代理自身的语义
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "TianGongServiceAgent[" + contract.getName() + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
                    default -> throw new IllegalStateException("不支持的 Object 方法: " + method.getName());
                };
            }
            // default 方法：按接口默认实现执行（不投递）
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }

            EntranceCall call = method.getAnnotation(EntranceCall.class);
            if (call == null) {
                throw new ServiceEntranceException(
                        "方法 [" + contract.getSimpleName() + "." + method.getName()
                                + "] 缺少 @EntranceCall（契约校验应已在启动期拦住）");
            }

            TianGongRuntime rt = runtime.get();
            ServiceEntranceRegistry registry = rt.registry();
            String body = (args == null || args.length == 0) ? null : JsonUtil.toJson(args[0]);

            // void：受理即返回（fire-and-forget），不等出口
            if (method.getReturnType() == void.class) {
                registry.submit(call.url(), call.workflow(), body);
                return null;
            }

            EntranceResponse response = registry.call(
                    call.url(), call.workflow(), body, rt.serviceTimeoutSeconds());

            // 原始异常：按真实类型 rethrow（受检异常 sneaky throw，保持类型保真）
            if (response.cause() != null) {
                rethrowFaithful(response.cause());
            }

            if (response.body() == null) {
                if (method.getReturnType() == Void.class) {
                    return null;
                }
                throw new ServiceEntranceException(
                        "服务入口 [" + call.url() + "] 未返回内容（body=null），"
                                + "但契约方法 [" + method.getName() + "] 声明了返回类型 "
                                + method.getReturnType().getSimpleName());
            }

            JavaType targetType = JsonUtil.getJSON_MAPPER().getTypeFactory()
                    .constructType(method.getGenericReturnType());
            try {
                return JsonUtil.getJSON_MAPPER().readValue(response.body(), targetType);
            } catch (Exception e) {
                throw new ServiceEntranceException(
                        "服务返回值反序列化失败: [" + call.url() + "] → "
                                + method.getReturnType().getSimpleName() + "，body=" + response.body(), e);
            }
        }

        /**
         * 类型保真的 rethrow：RuntimeException/Error 直接抛；受检异常 sneaky throw
         * （契约方法声明了该异常时，调用方照常按声明处理）。
         */
        @SuppressWarnings("unchecked")
        private static <T extends Throwable> void rethrowFaithful(Throwable cause) throws T {
            throw (T) cause;
        }
    }
}
