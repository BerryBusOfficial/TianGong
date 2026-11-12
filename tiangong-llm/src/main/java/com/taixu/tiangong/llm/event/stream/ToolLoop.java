package com.taixu.tiangong.llm.event.stream;

import java.util.List;

/**
 * 工具调用循环处理器：流读完一轮、拿到模型请求的工具调用后回调本接口，<br>
 * 由实现方执行工具并给出<b>携带工具结果的下一轮流</b>；返回 {@code null} 表示结束循环。<br>
 * <br>
 * <b>为什么是接口而不是直接实现</b>：执行工具需要 {@code FunctionRegistry} +
 * 反射调用能力，那是 tiangong-core 的职责；tiangong-llm 是纯协议层，只负责把多轮流串起来。
 * 循环的「怎么执行」通过本接口反转给上层（{@code FunctionToolLoop}），
 * 「何时循环、怎么聚合、回调怎么转发」留在 llm 层。
 */
public interface ToolLoop {

    /**
     * 执行本批工具调用，并返回继续对话的下一轮流。
     *
     * @param assistantContent 本轮助手的正文（模型可能边说话边调工具；空串表示没有说话）
     * @param calls            本轮完整工具调用（按出现顺序；arguments 已拼完整）
     * @return 下一轮流；{@code null} 表示不再继续（循环结束）
     */
    ChatStream<?, ?> next(String assistantContent, List<ToolCall> calls);
}
