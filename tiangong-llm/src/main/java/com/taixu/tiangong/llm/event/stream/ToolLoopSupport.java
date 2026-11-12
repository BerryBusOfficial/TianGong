package com.taixu.tiangong.llm.event.stream;

import com.taixu.tiangong.common.agent.call.AgentCallContext;
import com.taixu.tiangong.llm.api.request.chat.ChatRequest;
import com.taixu.tiangong.llm.client.LlmClient;
import com.taixu.tiangong.protocol.event.AgentEventJournal;
import com.taixu.tiangong.protocol.event.AgentEventScope;

import java.util.List;
import java.util.function.Function;

/**
 * 工具循环装配点：框架 core 启动时装入一个 {@link Factory}（策略：执行注册表里的函数），<br>
 * 各 provider 客户端在创建流时调用 {@link #wrap}，把「带工具的流」升级为「工具循环流」。<br>
 * <br>
 * <b>为什么用静态注入而不是构造器参数</b>：tiangong-llm 是纯协议层，不认识 core 的
 * {@code FunctionRegistry}；而流对象由客户端在任意调用点创建，没有统一的装配入口。
 * 静态注入是这两个约束下最轻的桥 —— 与 core 的 {@code ContextHolder} 同一思路：
 * 谁装配谁负责装，不装就退化为「单轮流」（纯 llm 模块、单测均不受影响）。
 */
public final class ToolLoopSupport {

    /**
     * 为一次流式对话创建循环处理器；返回 {@code null} 表示该请求不做自动循环
     * （如配置关闭、请求未带工具）。
     */
    @FunctionalInterface
    public interface Factory {
        ToolLoop create(ChatRequest request, LlmClient<?, ?> client);
    }

    private static volatile Factory factory;

    private ToolLoopSupport() {
    }

    /** 安装处理器工厂（框架启动时调用一次；测试可覆盖，传 null 卸载） */
    public static void install(Factory handlerFactory) {
        factory = handlerFactory;
    }

    /** 当前是否已安装工厂 */
    public static boolean installed() {
        return factory != null;
    }

    /**
     * 兼容入口：不提供正文抽取器时由事件桥按 {@code ChatChunkResponse#getContent()} 兜底。
     */
    public static <T, TCall> ChatStream<T, TCall> wrap(ChatStream<T, TCall> raw,
                                                       ChatRequest request,
                                                       LlmClient<?, ?> client,
                                                       Function<TCall, ToolCall> extractor) {
        return wrap(raw, request, client, extractor, null);
    }

    /**
     * 升级流：<b>请求带工具且已装配工厂</b>时返回循环流，否则原样返回裸流。<br>
     * <br>
     * 事件桥：只要当前线程有 {@link AgentEventScope.Binding}，即使没有工具，也会包一层
     * {@link EventEmittingChatStream} 把正文/思考/用量/终止发成类型化事件。绑定在
     * <b>创建流时</b>捕获，因为真正的读取在另一个虚拟线程里，ThreadLocal 不会自动继承。
     *
     * @param raw            裸流（单轮）
     * @param request        原始请求（循环层回放消息时以此为基础）
     * @param client         发起流的客户端（循环层用它续发下一轮）
     * @param extractor      把 provider 私有的调用类型映射为 {@link ToolCall}
     * @param deltaExtractor 把 provider 私有 chunk 映射为正文增量；null 时按
     *                       {@code ChatChunkResponse#getContent()} 兜底
     */
    public static <T, TCall> ChatStream<T, TCall> wrap(ChatStream<T, TCall> raw,
                                                       ChatRequest request,
                                                       LlmClient<?, ?> client,
                                                       Function<TCall, ToolCall> extractor,
                                                       Function<T, String> deltaExtractor) {
        if (raw == null) {
            return null;
        }
        AgentEventScope.Binding eventBinding = AgentEventScope.capture();
        AgentCallContext.Binding callBinding = AgentCallContext.capture();

        // 推理预算是最内层包装：先于工具循环/事件桥，保证超预算时取消的是供应商流本身，
        // 而不是已经进入工具回放之后才停。
        ChatStream<T, TCall> source = raw;
        if (request != null && request.getMaxReasoningTokens() != null
                && request.getMaxReasoningTokens() > 0) {
            source = new ReasoningBudgetChatStream<>(raw, request.getMaxReasoningTokens());
        }

        Factory f = factory;
        if (request != null && f != null) {
            List<ChatRequest.Tool> tools = request.getTools();
            if (tools != null && !tools.isEmpty()) {
                ToolLoop loop = f.create(request, client);
                if (loop != null) {
                    ChatStream<T, TCall> result = new ToolLoopChatStream<>(source, loop, extractor,
                            deltaExtractor, eventBinding, callBinding);
                    bindCancellation(eventBinding, result);
                    return result;
                }
            }
        }
        ChatStream<T, TCall> result = eventBinding == null ? source
                : new EventEmittingChatStream<>(source, deltaExtractor, eventBinding);
        bindCancellation(eventBinding, result);
        return result;
    }

    /**
     * 把「事件日志取消」接到「流取消」：SSE 客户端断开时 journal.cancel() 会真正
     * cancel 供应商流/工具循环，而不是只补一条 done=cancelled。
     * 只在事件出口确实是 journal 时绑定；纯事件 sink/单测不受影响。
     */
    private static void bindCancellation(AgentEventScope.Binding binding, ChatStream<?, ?> stream) {
        if (binding != null && binding.sink() instanceof AgentEventJournal journal) {
            journal.onCancel(stream::cancel);
        }
    }
}
