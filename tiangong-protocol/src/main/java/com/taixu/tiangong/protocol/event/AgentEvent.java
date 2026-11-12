package com.taixu.tiangong.protocol.event;

import java.util.Map;

/**
 * 一次 Agent 运行中可以被客户端消费的类型化事件。<br>
 * <br>
 * <h2>为什么要类型化</h2>
 * 旧链路把「正文 / 思考 / 工具调用 / 用量」都塞在供应商私有的 chunk 里，Web 与
 * Android 各自写一套解析；一旦供应商改字段，两边一起坏。这里把「Agent 语义」抽成
 * 稳定契约：前端只认 {@link AgentEventType}，不再认识 Deepseek/OpenAI 的 delta 结构。<br>
 * <br>
 * <h2>设计约束</h2>
 * <ul>
 *     <li>每个事件只表达一件事，便于按类型分发与回放；</li>
 *     <li>正文与思考分开（{@code message.delta} / {@code reasoning.delta}），
 *         不允许把思考混进正文再让客户端猜；</li>
 *     <li>工具调用同时给「开始」和「结果」两个事件，前端可以画出完整的工具时间线；</li>
 *     <li>{@code done} 是唯一的正常终止标记，{@code error} 之后仍应有一个 {@code done}，
 *         避免客户端为了判断"流结束没有"去猜连接关闭。</li>
 * </ul>
 */
public sealed interface AgentEvent permits
        AgentEvent.MessageDelta,
        AgentEvent.ReasoningDelta,
        AgentEvent.ToolCall,
        AgentEvent.ToolResult,
        AgentEvent.Citation,
        AgentEvent.Artifact,
        AgentEvent.Usage,
        AgentEvent.Structured,
        AgentEvent.Error,
        AgentEvent.Done {

    AgentEventType type();

    /** 正文增量（模型最终回答的一部分） */
    record MessageDelta(String delta) implements AgentEvent {
        @Override
        public AgentEventType type() {
            return AgentEventType.MESSAGE_DELTA;
        }
    }

    /** 思考增量（reasoning content）；与正文严格分离 */
    record ReasoningDelta(String delta) implements AgentEvent {
        @Override
        public AgentEventType type() {
            return AgentEventType.REASONING_DELTA;
        }
    }

    /**
     * 模型请求调用工具。
     *
     * @param callId    调用 id，结果回放时必须原样带回
     * @param name      工具名
     * @param arguments 参数 JSON 文本（模型给的原始文本，可能尚未校验）
     */
    record ToolCall(String callId, String name, String arguments) implements AgentEvent {
        @Override
        public AgentEventType type() {
            return AgentEventType.TOOL_CALL;
        }
    }

    /**
     * 工具执行完成。
     *
     * @param status     {@code success} / {@code failed} / {@code denied}
     * @param result     结果文本（JSON 或错误说明）
     * @param durationMs 执行耗时
     */
    record ToolResult(String callId, String name, String status, String result, long durationMs)
            implements AgentEvent {
        @Override
        public AgentEventType type() {
            return AgentEventType.TOOL_RESULT;
        }
    }

    /**
     * 检索引用。没有它，RAG 的「这句话从哪来」只能由每个产品自己拼。
     */
    record Citation(String docId, String title, String url, double score, String snippet)
            implements AgentEvent {
        @Override
        public AgentEventType type() {
            return AgentEventType.CITATION;
        }
    }

    /**
     * 产物（报告草稿、图表、文件等）。
     *
     * @param kind 产物类型（如 {@code report} / {@code chart} / {@code file}）
     * @param url  可访问地址；没有外部地址时可为 null
     */
    record Artifact(String artifactId, String kind, String name, String url, String mimeType,
                    Long sizeBytes) implements AgentEvent {
        @Override
        public AgentEventType type() {
            return AgentEventType.ARTIFACT;
        }
    }

    /** 本次调用的 token 用量（用于按节点/租户计费） */
    record Usage(long promptTokens, long completionTokens, long totalTokens, String model)
            implements AgentEvent {
        @Override
        public AgentEventType type() {
            return AgentEventType.USAGE;
        }
    }

    /**
     * 结构化输出已通过 schema 校验。
     *
     * @param schemaName schema 名（通常是目标类型简单名）
     * @param data       反序列化后的对象树；客户端可直接渲染，不必再从正文里抠 JSON
     */
    record Structured(String schemaName, Map<String, Object> data) implements AgentEvent {
        @Override
        public AgentEventType type() {
            return AgentEventType.STRUCTURED;
        }
    }

    /**
     * 错误事件。可重试错误（限流、瞬时网络失败）应标 {@code retryable=true}。
     */
    record Error(String code, String message, boolean retryable) implements AgentEvent {
        @Override
        public AgentEventType type() {
            return AgentEventType.ERROR;
        }
    }

    /**
     * 终止事件。
     *
     * @param status {@code completed} / {@code cancelled} / {@code failed}
     */
    record Done(String status) implements AgentEvent {
        @Override
        public AgentEventType type() {
            return AgentEventType.DONE;
        }
    }
}
