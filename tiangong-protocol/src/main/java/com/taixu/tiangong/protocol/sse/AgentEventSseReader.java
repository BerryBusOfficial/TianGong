package com.taixu.tiangong.protocol.sse;

import com.taixu.tiangong.protocol.event.AgentEventCodec;
import com.taixu.tiangong.protocol.event.AgentEventEnvelope;
import com.taixu.tiangong.protocol.event.AgentEventType;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * SSE 帧解析（客户端 SDK 的底层）。<br>
 * <br>
 * 按 SSE 规范处理：字段 {@code id / event / data}、冒号后的可选空格、多行 data、
 * 注释行与空行分帧。**不认识的事件类型会被跳过**，这样服务端先上线新事件时，
 * 老客户端不会整条流解析失败。
 */
public final class AgentEventSseReader {

    private AgentEventSseReader() {
    }

    /**
     * 连续读取直到流结束。
     *
     * @return 最后一条事件的 seq（没有事件则为 0），可直接作为下次的 Last-Event-ID
     */
    public static long read(InputStream input, Consumer<AgentEventEnvelope> consumer) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        long lastId = 0;
        AgentEventEnvelope envelope;
        while ((envelope = next(reader)) != null) {
            lastId = envelope.seq();
            consumer.accept(envelope);
        }
        return lastId;
    }

    /**
     * 读取下一条可识别事件；流结束返回 {@code null}。<br>
     * 注释/未知事件会被跳过并继续读，直到遇到一条可识别事件或 EOF。
     */
    public static AgentEventEnvelope next(BufferedReader reader) throws IOException {
        String id = null;
        String event = null;
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                AgentEventEnvelope parsed = dispatch(id, event, data);
                if (parsed != null) {
                    return parsed;
                }
                // 空帧或未知事件：清空状态继续读
                id = null;
                event = null;
                data.setLength(0);
                continue;
            }
            if (line.startsWith(":")) {
                continue;                       // 注释/心跳
            }
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);     // SSE 规范：只去掉一个前导空格
            }
            switch (field) {
                case "id" -> id = value;
                case "event" -> event = value;
                case "data" -> {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(value);
                }
                case "retry" -> {
                    // 客户端重连策略由连接层决定；帧层只认字段，不消费 retry
                }
                default -> {
                    // 未知字段按 SSE 规范忽略
                }
            }
        }
        // EOF 前最后一个帧可能没有结尾空行
        return dispatch(id, event, data);
    }

    private static AgentEventEnvelope dispatch(String id, String event, StringBuilder data) {
        if (data.isEmpty()) {
            return null;
        }
        AgentEventType type = AgentEventType.fromWireName(event);
        if (type == null) {
            return null;
        }
        AgentEventEnvelope envelope = AgentEventCodec.fromJson(data.toString());
        if (envelope == null) {
            return null;
        }
        // id 与 data.seq 不一致说明服务端帧损坏；以 data 里的 seq 为准（它是业务真值），
        // 但保留 id 字段的校验价值：客户端重连用的是它。
        if (id != null && !id.isBlank()) {
            try {
                long frameId = Long.parseLong(id.trim());
                if (frameId != envelope.seq()) {
                    throw new IllegalStateException(
                            "SSE 帧 id=" + frameId + " 与事件 seq=" + envelope.seq() + " 不一致");
                }
            } catch (NumberFormatException ignored) {
                // 非数字 id 不是本协议的约定，忽略但继续
            }
        }
        return envelope;
    }
}
