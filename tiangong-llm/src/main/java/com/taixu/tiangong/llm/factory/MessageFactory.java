package com.taixu.tiangong.llm.factory;

import com.taixu.tiangong.llm.message.AssistantMessage;
import com.taixu.tiangong.llm.message.HumanMessage;
import com.taixu.tiangong.llm.message.SystemMessage;
import com.taixu.tiangong.llm.message.ToolMessage;

import java.util.List;

public class MessageFactory {

    private MessageFactory() {
    }

    public static ToolMessage createToolMsg(String content, String toolCallId) {
        return new ToolMessage(content, toolCallId);
    }

    public static SystemMessage createSystemMsg(String content) {
        return new SystemMessage(content);
    }

    public static SystemMessage createSystemMsg(String content, String name) {
        return new SystemMessage(content, name);
    }

    public static HumanMessage createHumanMsg(String content) {
        return new HumanMessage(content);
    }

    public static HumanMessage createHumanMsg(String content, String name) {
        return new HumanMessage(content, name);
    }

    public static HumanMessage createHumanMsg(String content, List<String> images) {
        return new HumanMessage(content, images, null);
    }

    public static HumanMessage createHumanMsg(String content, List<String> images, String name) {
        return new HumanMessage(content, images, name);
    }

    public static AssistantMessage createAssistantMsg(String content) {
        return new AssistantMessage(content);
    }

    public static SystemMessage createAssistantMsg(String content, String name) {
        return new SystemMessage(content, name);
    }
}
