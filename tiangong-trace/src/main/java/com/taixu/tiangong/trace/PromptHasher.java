package com.taixu.tiangong.trace;

import com.taixu.tiangong.llm.message.AssistantMessage;
import com.taixu.tiangong.llm.message.Message;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * prompt 指纹：完整 prompt 的 SHA-256。<br>
 * 用于「同 prompt 是否复现同一问题」与 golden trace 比对；不把原文落进 trace。
 */
public final class PromptHasher {

    private PromptHasher() {
    }

    public static String hash(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return hash("");
        }
        StringBuilder text = new StringBuilder();
        for (Message message : messages) {
            if (message == null) {
                continue;
            }
            text.append(message.getRole()).append('\u0000')
                    .append(message.getContent() == null ? "" : message.getContent())
                    .append('\u0000');
            if (message instanceof AssistantMessage assistant
                    && assistant.getToolCalls() != null) {
                for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                    text.append(call.getId()).append('\u0000')
                            .append(call.getFunction() == null ? "" : call.getFunction().name())
                            .append('\u0000')
                            .append(call.getFunction() == null ? "" : call.getFunction().arguments())
                            .append('\u0000');
                }
            }
        }
        return hash(text.toString());
    }

    public static String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }
}
