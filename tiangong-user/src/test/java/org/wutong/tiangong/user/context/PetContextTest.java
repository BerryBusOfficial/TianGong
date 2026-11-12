package org.wutong.tiangong.user.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wutong.tiangong.llm.message.HumanMessage;
import org.wutong.tiangong.llm.message.Message;
import org.wutong.tiangong.user.history.PetHistory;
import org.wutong.tiangong.user.memory.PetMemory;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 上下文的行为。<br>
 * <b>不起真实容器</b>：tiangong-user 标了 {@code @EnableAgentLoop}，起容器会触发 START 轮询，
 * 工作流会跑到 STDIN 入口永久阻塞。这里一律手工装配（与运行期的差别只是没有容器注入）。
 */
class PetContextTest {

    @TempDir
    Path dir;

    private PetContext context(int windows) {
        PetMemory memory = new PetMemory();
        memory.setMemoryPath(dir.resolve("memory.json"));
        PetHistory history = new PetHistory();
        history.setHistoryPath(dir.resolve("history.json"));

        PetContext context = new PetContext();
        context.setWindows(windows);
        context.setSystemInput("你是墨兮，一只桌宠。");
        context.setMemory(memory);
        context.setHistory(history);
        return context;
    }

    @Test
    @DisplayName("init 只装载一次：重复调用不会把系统提示词塞两遍")
    void initOnce() {
        PetContext context = context(10);

        context.init();
        context.init();

        assertEquals(1, context.getContext().size(), "init 有幂等守卫，重复调用不应重复装载");
        assertTrue(context.getContext().get(0).getContent().contains("墨兮"));
    }

    @Test
    @DisplayName("窗口满了也不淘汰系统提示词")
    void promptSurvivesWindowTrim() {
        PetContext context = context(3);
        context.init();

        for (int i = 1; i <= 5; i++) {
            context.append(new HumanMessage("第" + i + "句"));
        }

        assertEquals(3, context.getContext().size());
        assertTrue(context.getContext().get(0).getContent().contains("墨兮"),
                "系统提示词被挤掉等于人格丢失，比少看一轮历史严重得多");
        assertTrue(context.getContext().get(2).getContent().contains("第5句"));
    }

    @Test
    @DisplayName("每轮刷新状态：旧的被替换，提示词里永远只有一份")
    void refreshStateReplacesPrevious() {
        PetContext context = context(10);
        context.init();
        context.getMemory().remember("称呼", "梧桐");
        context.getMemory().setMood(org.wutong.tiangong.user.model.Mood.HAPPY);

        context.refreshState();
        context.refreshState();

        long stateMessages = context.getContext().stream()
                .filter(m -> PetContext.STATE_MESSAGE_NAME.equals(m.getName()))
                .count();
        assertEquals(1, stateMessages, "每轮都刷新的话，不替换旧状态会让提示词越聊越长");
        assertTrue(context.stateText().contains("梧桐"));
        assertTrue(context.stateText().contains("开心"));
    }

    @Test
    @DisplayName("append 进历史：sessionId 用来区分是哪条会话说的")
    void appendRecordsHistory() {
        PetContext context = context(10);
        context.init();
        context.append(new HumanMessage("你好"));

        assertEquals(1, context.getHistory().size());
        assertEquals(context.getSessionId(),
                context.getHistory().recent(1).get(0).getSessionId());
    }

    @Test
    @DisplayName("afterEnd：清掉本轮工具痕迹 + 记忆演化一轮")
    void afterEndClearsTraceAndEvolves() {
        PetContext context = context(10);
        context.init();
        context.markToolCall("set_mood", "call-1", null);
        assertTrue(context.isToolCall());

        context.afterEnd();

        assertFalse(context.isToolCall(), "工具痕迹不清会让下一轮 decide 误判");
        assertNull(context.getNowTool());
        assertEquals(1, context.getMemory().state().getTotalTurns());
        assertEquals(78, context.getMemory().state().getEnergy());
    }

    @Test
    @DisplayName("destroy 后重新 init 要能再装载：标志位必须重置")
    void destroyResetsForReuse() {
        PetContext context = context(10);
        context.init();
        context.cancel();

        context.destroy();

        assertTrue(context.getContext().isEmpty());
        assertFalse(context.isCancelled(), "cancelled 不重置则新工作流一启动就被判为已取消");

        context.init();
        assertEquals(1, context.getContext().size(), "initiated 不重置则下次 init 被守卫挡掉");
    }

    @Test
    @DisplayName("copy：带上已装载的提示词，协作对象仍指向同一个单例")
    void copyKeepsPromptAndSharesCollaborators() {
        PetContext context = context(10);
        context.init();
        context.append(new HumanMessage("你好"));

        PetContext copy = (PetContext) context.copy();

        assertEquals(context.getContext().size(), copy.getContext().size(),
                "fork 出来的副本要带着系统提示词，否则请求级上下文是「没人格」的");
        assertSame(context.getMemory(), copy.getMemory(), "记忆是共享资源，不该被 fork 出副本");
        assertSame(context.getHistory(), copy.getHistory());
        assertNotSame(context.getSessionId(), copy.getSessionId());

        copy.init();
        assertEquals(context.getContext().size(), copy.getContext().size(),
                "副本不应再装载一次系统提示词");
    }

    @Test
    @DisplayName("未装配记忆时也能跑：状态文本退化为空串而不是 NPE")
    void worksWithoutMemory() {
        PetContext context = new PetContext();
        context.setWindows(5);
        context.setSystemInput("你是墨兮");

        context.init();
        context.refreshState();
        context.afterEnd();
        context.destroy();

        assertEquals("", context.stateText());
    }

    @Test
    @DisplayName("窗口淘汰后消息仍在历史里：窗口是上下文预算，不是记忆")
    void historyKeepsTrimmedMessages() {
        PetContext context = context(3);
        context.init();
        for (int i = 1; i <= 5; i++) {
            context.append(new HumanMessage("第" + i + "句"));
        }

        assertEquals(3, context.getContext().size());
        assertEquals(5, context.getHistory().size(), "被窗口淘汰的消息仍应留在历史里");
    }

    @Test
    @DisplayName("append 原样透传 content —— 往里塞 JSON 会让模型模仿着用 JSON 回话")
    void appendPassesContentThrough() {
        PetContext context = context(10);
        Message appended = context.append(new HumanMessage("你好"));

        assertEquals("你好", appended.getContent(), "content 必须是原文，不能包 JSON");
        assertEquals("你好", context.getLastestMessage(),
                "lastestMessage 也应是原文，否则字段语义失真");
    }

    @Test
    @DisplayName("时间感由系统状态消息提供，不污染每条消息")
    void stateTextCarriesClock() {
        PetContext context = context(10);
        assertTrue(context.stateText().contains("当前时间："),
                "时间放在系统消息里，模型照样有钟，但历史消息是干净的");
    }
}
