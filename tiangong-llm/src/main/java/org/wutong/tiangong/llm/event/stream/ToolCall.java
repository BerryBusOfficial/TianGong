package org.wutong.tiangong.llm.event.stream;

/**
 * 供应商无关的工具调用快照：工具循环层看到的统一形态。<br>
 * <br>
 * 各 provider 的流把自家的工具调用增量映射为本记录（OpenAI / Deepseek 协议给的是
 * 分片 delta、arguments 为 JSON 文本；Ollama 给的是结构化对象），循环层因此不必认识
 * 任何一家的响应类型。
 *
 * @param id        调用 id —— <b>回放时必须原样带回</b>：OpenAI 协议靠它把工具结果
 *                  对回对应的调用，丢了它会 400（tool 消息找不到前置 tool_calls）
 * @param name      工具名（注册表索引，如 {@code get_sys_time}）
 * @param arguments 参数 JSON 文本；模型调无参工具时可能是空串
 */
public record ToolCall(String id, String name, String arguments) {
}
