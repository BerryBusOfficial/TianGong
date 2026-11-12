package com.taixu.tiangong.llm.event.stream;

import com.taixu.tiangong.llm.api.request.chat.ChatRequest;
import com.taixu.tiangong.llm.client.LlmClient;

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
     * 升级流：<b>请求带工具且已装配工厂</b>时返回循环流，否则原样返回裸流。<br>
     * <br>
     * 三个前置条件缺一不可：
     * <ol>
     *     <li>工厂已安装 —— 纯 llm 环境不循环；</li>
     *     <li>请求下发了工具 —— 没有工具就不可能有工具调用；</li>
     *     <li>工厂愿意受理 —— 框架配置（{@code agent.tools.auto-loop}）可整体关闭。</li>
     * </ol>
     *
     * @param raw       裸流（单轮）
     * @param request   原始请求（循环层回放消息时以此为基础）
     * @param client    发起流的客户端（循环层用它续发下一轮）
     * @param extractor 把 provider 私有的调用类型映射为 {@link ToolCall}
     */
    public static <T, TCall> ChatStream<T, TCall> wrap(ChatStream<T, TCall> raw,
                                                       ChatRequest request,
                                                       LlmClient<?, ?> client,
                                                       Function<TCall, ToolCall> extractor) {
        Factory f = factory;
        if (raw == null || f == null || request == null) {
            return raw;
        }
        List<ChatRequest.Tool> tools = request.getTools();
        if (tools == null || tools.isEmpty()) {
            return raw;
        }
        ToolLoop loop = f.create(request, client);
        if (loop == null) {
            return raw;
        }
        return new ToolLoopChatStream<>(raw, loop, extractor);
    }
}
