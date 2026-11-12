package com.taixu.tiangong.protocol.sse;

import com.taixu.tiangong.protocol.event.AgentEventCodec;
import com.taixu.tiangong.protocol.event.AgentEventEnvelope;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * SSE 帧编码：把事件写成标准 {@code id/event/data} 三行 + 空行。<br>
 * <br>
 * 只做帧，不做 HTTP：服务端把 {@link OutputStream} 换成 {@code HttpExchange} 的响应体即可。
 * 这样同一套帧逻辑可以接到 JDK HttpServer、Servlet、Reactor 或测试用的内存流上。
 */
public final class AgentEventSseWriter {

    private AgentEventSseWriter() {
    }

    public static void write(OutputStream out, AgentEventEnvelope envelope) throws IOException {
        StringBuilder frame = new StringBuilder(128);
        frame.append("id: ").append(envelope.seq()).append('\n');
        frame.append("event: ").append(envelope.eventName()).append('\n');
        frame.append("data: ").append(AgentEventCodec.toJsonString(envelope)).append('\n');
        frame.append('\n');
        out.write(frame.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * 心跳注释。长连接在模型思考/工具执行期间可能几十秒没有事件；
     * 不发注释会被代理/负载均衡器当成空闲连接掐掉。
     */
    public static void writeComment(OutputStream out, String comment) throws IOException {
        String text = ": " + (comment == null ? "ping" : comment.replace('\n', ' ')) + "\n\n";
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
