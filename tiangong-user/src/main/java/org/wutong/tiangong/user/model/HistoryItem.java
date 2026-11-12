package org.wutong.tiangong.user.model;

import lombok.Data;
import org.wutong.tiangong.llm.message.Message;

/**
 * 一条历史记录。<br>
 * <br>
 * <h2>为什么存字段而不是存 {@link Message} 对象</h2>
 * 历史要落盘再读回。{@code Message} 是框架的消息载体，带角色子类与可能的附加字段，
 * 把它直接序列化会让落盘格式绑死在框架类型上 —— 框架一改，历史文件就读不回来了。<br>
 * 这里只落 {@code role / content / time} 三个字段，读回来按需重建即可。
 * 这是历史与「记忆」的共同原则：<b>持久化格式自己定，不沿用运行期类型</b>。
 */
@Data
public class HistoryItem {

    private String itemId;
    private String sessionId;
    private String role;
    private String content;
    private String time;

    public HistoryItem() {
    }

    public HistoryItem(String sessionId, Message message) {
        this.sessionId = sessionId;
        this.role = message == null ? "unknown" : String.valueOf(message.getRole());
        this.content = message == null ? "" : message.getContent();
        this.time = java.time.LocalDateTime.now().toString();
        this.itemId = org.wutong.tiangong.common.util.IdGenerator.generate(
                org.wutong.tiangong.common.enums.Namespace.AGENT,
                org.wutong.tiangong.common.enums.ResourceType.SESSION);
    }
}
