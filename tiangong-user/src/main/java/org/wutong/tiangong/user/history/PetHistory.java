package org.wutong.tiangong.user.history;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import com.taixu.tiangong.annotations.annotation.framework.Component;
import com.taixu.tiangong.annotations.annotation.framework.inject.Value;
import com.taixu.tiangong.common.agent.capability.AgentCapability;
import com.taixu.tiangong.llm.message.Message;
import org.wutong.tiangong.user.model.HistoryItem;
import org.wutong.tiangong.user.model.HistorySnapshot;
import org.wutong.tiangong.user.store.JsonFileStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 桌宠的对话历史：跨会话、跨重启留存的「说过什么」。<br>
 * <br>
 *
 * <h2>与记忆（{@code PetMemory}）的分工</h2>
 * <ul>
 *     <li><b>历史</b>：流水账，只增不改，回答「我们之前聊过什么」</li>
 *     <li><b>记忆</b>：提炼过的状态与事实，会覆盖、会演化，回答「我是谁、你是谁」</li>
 * </ul>
 * 一期不做「从历史里自动提炼记忆」：那需要额外一次 LLM 调用，且提炼错了
 * 比没提炼更糟（错误事实会被一直当真）。历史先原样留着，人能看、能搜就够了。
 *
 * <h2>为什么由上下文来给 sessionId</h2>
 * 本类是容器<b>单例</b>，被 STDIN 与 API 两条工作流共享。若由它自己生成 sessionId，
 * 两条链的发言会混在同一段里、分不清是谁说的。因此 {@link #add} 要求调用方
 * （即 {@code PetContext}）传入自己的 sessionId —— 一条工作流 = 一段会话。
 *
 * <h2>线程安全</h2>
 * 两条链共享本单例，写入与落盘全部 {@code synchronized}。
 */
@Slf4j
@Component
public class PetHistory implements AgentCapability {

    public static final String DEFAULT_PATH = "data/history.json";

    /**
     * 落盘条数上限。<br>
     * 历史只增不减，不设上限文件会无限长：既是磁盘问题，也是启动加载时间问题。
     * 超出时淘汰<b>最旧</b>的 —— 桌宠场景里最近的对话才有用。
     */
    public static final int MAX_ITEMS = 500;

    @Value(value = "pet.history.path", defaultValue = DEFAULT_PATH)
    private String historyPath = DEFAULT_PATH;

    @Getter
    private final List<HistoryItem> items = new CopyOnWriteArrayList<>();

    private volatile boolean loaded = false;

    /** 显式指定落盘路径（测试与特殊部署用） */
    public synchronized void setHistoryPath(Path path) {
        this.historyPath = path == null ? DEFAULT_PATH : path.toString();
        this.loaded = false;
    }

    public Path path() {
        return Path.of(historyPath == null ? DEFAULT_PATH : historyPath);
    }

    /**
     * 加载。幂等；重复调用只在实际读到文件时刷新内存态。<br>
     * 懒加载而非构造时加载：{@code @Value} 在容器 refresh 阶段才注入，
     * 构造器中读路径拿到的还是默认值。
     */
    public synchronized void load() {
        if (loaded) {
            return;
        }
        HistorySnapshot snapshot = JsonFileStore.read(path(), HistorySnapshot.class).orElse(null);
        if (snapshot == null) {
            log.info("未找到历史文件，从空历史开始: {}", path());
        } else {
            items.clear();
            items.addAll(snapshot.safeItems());
            log.info("已加载历史：{} 条（session={}）", items.size(), snapshot.getSessionId());
        }
        loaded = true;
    }

    /** 落盘。写入失败只告警（见 {@link JsonFileStore#write}） */
    public synchronized void save() {
        if (!loaded) {
            return;   // 从未加载过就不要写，避免用空列表覆盖真实文件
        }
        HistorySnapshot snapshot = new HistorySnapshot();
        snapshot.setItems(new ArrayList<>(trimmed()));
        JsonFileStore.write(path(), snapshot);
    }

    /**
     * 记一条。{@code message} 为 null 时忽略（不写空记录占位）。<br>
     * <br>
     * <b>只写内存，不落盘</b>：落盘时机统一由 {@code PetContext.afterEnd} 每轮收尾时触发，
     * 避免一条消息一次 IO（一轮对话可能产生系统/用户/助手/工具四条）。
     */
    public synchronized void add(String sessionId, Message message) {
        load();
        if (message == null) {
            return;
        }
        items.add(new HistoryItem(sessionId, message));
    }

    /** 最近 n 条（按写入顺序，末尾为最新） */
    public synchronized List<HistoryItem> recent(int n) {
        load();
        if (n <= 0) {
            return List.of();
        }
        List<HistoryItem> all = new ArrayList<>(items);
        return List.copyOf(all.subList(Math.max(0, all.size() - n), all.size()));
    }

    public synchronized int size() {
        load();
        return items.size();
    }

    /** 清空内存与文件（仅供测试与「重置桌宠」用） */
    public synchronized void clear() {
        load();
        items.clear();
        save();
    }

    /** 超出上限时裁掉的列表（保留最新的 {@link #MAX_ITEMS} 条） */
    private List<HistoryItem> trimmed() {
        List<HistoryItem> all = new ArrayList<>(items);
        if (all.size() <= MAX_ITEMS) {
            return all;
        }
        return new ArrayList<>(all.subList(all.size() - MAX_ITEMS, all.size()));
    }
}
