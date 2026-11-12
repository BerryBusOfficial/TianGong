package com.taixu.tiangong.guardrail;

import com.taixu.tiangong.common.agent.call.AgentCallContext;

import java.util.Map;
import java.util.function.Function;

/**
 * 护栏入口：进程级默认链 + 按租户解析的链。<br>
 * <br>
 * 默认链为空——不安装就完全不改变现有行为（向后兼容）。生产可以在启动期调用
 * {@link #install(GuardrailChain)} 或 {@link #install(Function)} 配置租户差异。
 */
public final class GuardrailService {

    private static volatile GuardrailChain defaultChain = GuardrailChain.empty();
    private static volatile Function<AgentCallContext, GuardrailChain> resolver;

    private GuardrailService() {
    }

    public static void install(GuardrailChain chain) {
        defaultChain = chain == null ? GuardrailChain.empty() : chain;
    }

    /** 按调用者选择链；返回 null 时回退到默认链 */
    public static void install(Function<AgentCallContext, GuardrailChain> chainResolver) {
        resolver = chainResolver;
    }

    public static void reset() {
        defaultChain = GuardrailChain.empty();
        resolver = null;
    }

    public static GuardrailChain chainFor(AgentCallContext context) {
        Function<AgentCallContext, GuardrailChain> current = resolver;
        if (current != null) {
            GuardrailChain resolved = current.apply(context);
            if (resolved != null) {
                return resolved;
            }
        }
        return defaultChain;
    }

    public static GuardrailResult inspect(GuardrailStage stage, String text,
                                          AgentCallContext context,
                                          Map<String, Object> metadata) {
        GuardrailContext guardContext = new GuardrailContext(stage, text, context, metadata);
        return chainFor(context).inspect(guardContext);
    }

    public static GuardrailResult inspect(GuardrailStage stage, String text,
                                          AgentCallContext context) {
        return inspect(stage, text, context, Map.of());
    }

    /**
     * 同 {@link #inspect}，但 BLOCK 时抛 {@link GuardrailBlockedException}；
     * 适合「必须拦截」的入口（如用户输入）。
     */
    public static String require(GuardrailStage stage, String text,
                                 AgentCallContext context, Map<String, Object> metadata) {
        GuardrailResult result = inspect(stage, text, context, metadata);
        if (result.blocked()) {
            throw new GuardrailBlockedException(stage, result.reason());
        }
        return result.text();
    }
}
